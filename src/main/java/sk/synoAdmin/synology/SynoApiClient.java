package sk.synoAdmin.synology;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation.Builder;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import sk.synoAdmin.LogUtil;
import sk.synoAdmin.UserException;
import sk.synoAdmin.engine.Folder;
import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.synology.pojo.SynoAccessRightResponse;
import sk.synoAdmin.synology.pojo.SynoLoginResponse;
import sk.synoAdmin.synology.pojo.SynoPermissionResponse;
import sk.synoAdmin.synology.pojo.SynoSetAccessRightResponse;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class SynoApiClient {

	private final Preferences pref;
	private final WebTarget webTarget;
	private Cookie cookieSession;
	private Cookie cookieDeviceId;
	private String synoToken;

	public SynoApiClient(final Preferences pref) {
		this.pref = pref;
		final Client client = ClientBuilder.newClient();
		webTarget = client.target(pref.getUrl());
	}

	public CompletableFuture<Void> loginAsynch() {
		return CompletableFuture.runAsync(this::login);
	}

	private void initCookies() {
		if (pref.getCookieSession() != null) {
			cookieSession = new Cookie.Builder("id")
					.value(pref.getCookieSession())
					.path("/")
					.build();
		}
		if (pref.getCookieDid() != null) {
			cookieDeviceId = new Cookie.Builder("did")
					.value(pref.getCookieDid())
					.path("/")
					.build();
		}
		if (pref.getSynoToken() != null) {
			synoToken = pref.getSynoToken();
		}
	}

	public void login() {
		LogUtil.runMonitored(() -> {
			initCookies();

			boolean isLogged = false;
			if (pref.getCookieSession() != null) {
				final Form form = new Form();
				form.param("api", "SYNO.Core.Desktop.Timeout");
				form.param("method", "check");
				form.param("version", "1");

				final var response = doRequest("/webapi/entry.cgi", form);
				SynoLoginResponse responseParsed = response.readEntity(SynoLoginResponse.class);
				isLogged = responseParsed.isSuccess();
			}

			if (!isLogged) {
				// try to login with password
				final Form form = new Form();
				form.param("api", "SYNO.API.Auth");
				form.param("method", "login");
				form.param("enable_syno_token", "yes");
				form.param("rememberme", "1");
				form.param("version", "7");
				form.param("account", pref.getUser());
				final String pwd;
				if (pref.getPassword() != null) {
					pwd = pref.getPassword();
				} else {
					System.out.println("Password ?");
					pwd = new Scanner(System.in).next();
				}
				form.param("passwd", pwd);

				var response = doRequest("/webapi/entry.cgi?api=SYNO.API.Auth", form);

				SynoLoginResponse responseParsed = response.readEntity(SynoLoginResponse.class);
				if (!responseParsed.isSuccess() && "otp".equals(responseParsed.getError().getErrorType())) {
					System.out.println("OTP ?");
					final String otp = new Scanner(System.in).next();
					form.param("otp_code", otp);
					form.param("enable_device_token", "yes");

					response = doRequest("/webapi/entry.cgi?api=SYNO.API.Auth", form);
					responseParsed = response.readEntity(SynoLoginResponse.class);
				}

				if (responseParsed.isSuccess()) {
					isLogged = true;
					pref.setCookieSession(responseParsed.getSuccessData().getSid());
					pref.setCookieDid(responseParsed.getSuccessData().getDeviceId());
					pref.setSynoToken(responseParsed.getSuccessData().getSynotoken());
					initCookies();
				}
			}

			if (!isLogged) {
				throw new UserException("Login : fail");
			}

			// https://community.synology.com/enu/forum/1/post/145996
		}, "Login time");
	}

	public List<SynoPermissionResponse> getAccessRight() {
		List<SynoPermissionResponse> list = new ArrayList<>();

		SynoAccessRightResponse responseData = getSynoAccessRightResponse(null);
		list.addAll(responseData.getPermissions());

		for (var rootFolder : responseData.getPermissions()) {
			list.addAll(getSynoAccessRightResponse(rootFolder.getId()).getPermissions());
		}

		return list;
	}

	public SynoAccessRightResponse getSynoAccessRightResponse(Integer id) {
		LogUtil.debug("List folders for id " + id);

		final Form form = new Form();
		form.param("api", "SYNO.FotoTeam.Browse.Folder");
		form.param("method", "list");
		form.param("version", "2");
		if (id != null) {
			form.param("id", id.toString());
		}
		form.param("offset", "0");
		form.param("limit", "5000");
		form.param("additional", "[\"sharing_info\"]");

		final var response = doRequest("/webapi/entry.cgi/SYNO.FotoTeam.Browse.Folder", form);
		SynoAccessRightResponse responseData = response.readEntity(SynoAccessRightResponse.class);

		if (!responseData.isSuccess()) {
			throw new UserException("Impossible de récupérer les droits");
		}

		return responseData;
	}


	private String buildPermissionActionString(List<Folder> folders, String role) {
		String action = role == null ? "delete" : "update";
		String jsonRole = role != null ? ",\"role\":\"" + role + "\"" : "";
		return folders.stream()
				.map(folder -> "{\"folder_id\":" + folder.getId() + ",\"action\":\"" + action + "\"" + jsonRole + "}")
				.collect(Collectors.joining(","));
	}

	public SynoSetAccessRightResponse setAccessRight(final String gid, final List<Folder> foldersToAddList, final List<Folder> foldersToDeleteList) {
		final Form form = new Form();
		form.param("api", "SYNO.FotoTeam.Sharing.FolderBatchPermission");
		form.param("version", "1");
		form.param("method", "update_by_member");

		String permissions = buildPermissionActionString(foldersToAddList, "download");
		if (permissions.length() > 0 && foldersToDeleteList.size() > 0) {
			permissions += ",";
		}
		permissions += buildPermissionActionString(foldersToDeleteList, null);

		form.param("permission", "{\"id\":" + gid + ",\"type\":\"group\",\"list\":[" + permissions + "]}");

		final Response resp = doRequest("/webapi/entry.cgi/SYNO.FotoTeam.Sharing.FolderBatchPermission", form);

		final SynoSetAccessRightResponse synoResponse = resp.readEntity(SynoSetAccessRightResponse.class);

		if (synoResponse == null || !synoResponse.isSuccess()) {
			throw new UserException("Impossible de setter les droits");
		}

		return synoResponse;
	}

	public SynoSetAccessRightResponse addAccessRight(final String gid, final Folder... foldersToAdd) {
		return setAccessRight(gid, Arrays.asList(foldersToAdd), new ArrayList<>(0));
	}

	public SynoSetAccessRightResponse removeAccessRight(final String gid, final Folder... folderToDelete) {
		return setAccessRight(gid, new ArrayList<>(0), Arrays.asList(folderToDelete));
	}


	private Response doRequest(final String path, final Form form) {
		final Builder builder = webTarget
				.path(path)
				.request();

		if (cookieSession != null) {
			builder.cookie(cookieSession);
		}
		if (cookieDeviceId != null) {
			builder.cookie(cookieDeviceId);
		}
		if (synoToken != null) {
			builder.header("X-SYNO-TOKEN", synoToken);
		}

		final Response response = builder.post(Entity.form(form));

		return response;
	}
}
