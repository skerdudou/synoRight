package sk.synoAdmin.synology;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import sk.synoAdmin.ConsoleUtil;
import sk.synoAdmin.LogUtil;
import sk.synoAdmin.UserException;
import sk.synoAdmin.engine.Folder;
import sk.synoAdmin.prefs.Preferences;

public class SynoApiClient {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final Preferences pref;
	private final HttpClient httpClient;
	private String cookieHeader;
	private String synoToken;

	public SynoApiClient(final Preferences pref) {
		this.pref = pref;
		httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				// suit les redirections (comportement du client Jersey d'origine)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	public CompletableFuture<Void> loginAsynch() {
		return CompletableFuture.runAsync(this::login);
	}

	private void initCookies() {
		final StringBuilder cookies = new StringBuilder();
		if (pref.getCookieSession() != null) {
			cookies.append("id=").append(pref.getCookieSession());
		}
		if (pref.getCookieDid() != null) {
			if (cookies.length() > 0) {
				cookies.append("; ");
			}
			cookies.append("did=").append(pref.getCookieDid());
		}
		cookieHeader = cookies.length() > 0 ? cookies.toString() : null;

		if (pref.getSynoToken() != null) {
			synoToken = pref.getSynoToken();
		}
	}

	public void login() {
		LogUtil.runMonitored(() -> {
			initCookies();

			boolean isLogged = false;
			if (pref.getCookieSession() != null) {
				final Map<String, String> params = new LinkedHashMap<>();
				params.put("api", "SYNO.Core.Desktop.Timeout");
				params.put("method", "check");
				params.put("version", "1");

				final JsonNode response = doRequest("/webapi/entry.cgi", params);
				isLogged = isSuccess(response);
			}

			if (!isLogged) {
				// try to login with password
				final Map<String, String> params = new LinkedHashMap<>();
				params.put("api", "SYNO.API.Auth");
				params.put("method", "login");
				params.put("enable_syno_token", "yes");
				params.put("rememberme", "1");
				params.put("version", "7");
				params.put("account", pref.getUser());
				final String pwd;
				if (pref.getPassword() != null) {
					pwd = pref.getPassword();
				} else {
					pwd = ConsoleUtil.promptSecret("Password ?");
				}
				params.put("passwd", pwd);

				JsonNode response = doRequest("/webapi/entry.cgi?api=SYNO.API.Auth", params);

				if (!isSuccess(response) && "otp".equals(loginErrorType(response))) {
					final String otp = ConsoleUtil.promptLine("OTP ?");
					params.put("otp_code", otp);
					params.put("enable_device_token", "yes");

					response = doRequest("/webapi/entry.cgi?api=SYNO.API.Auth", params);
				}

				if (isSuccess(response)) {
					isLogged = true;
					final JsonNode data = response.path("data");
					pref.setCookieSession(data.path("sid").asText(null));
					pref.setCookieDid(data.path("device_id").asText(null));
					pref.setSynoToken(data.path("synotoken").asText(null));
					initCookies();
				}
			}

			if (!isLogged) {
				throw new UserException("Login : fail");
			}

			// https://community.synology.com/enu/forum/1/post/145996
		}, "Login time");
	}

	private static boolean isSuccess(final JsonNode response) {
		return response.path("success").asBoolean();
	}

	// type de la première erreur d'une réponse de login (ex : "otp")
	private static String loginErrorType(final JsonNode response) {
		return response.path("error").path("errors").path("types").path(0).path("type").asText(null);
	}

	/**
	 * Liste les groupes locaux du NAS (API SYNO.Core.Group).
	 */
	public List<SynoGroup> getGroups() {
		final Map<String, String> params = new LinkedHashMap<>();
		params.put("api", "SYNO.Core.Group");
		params.put("method", "list");
		params.put("version", "1");
		params.put("offset", "0");
		params.put("limit", "5000");
		params.put("type", "local");

		final JsonNode response = doRequest("/webapi/entry.cgi", params);

		if (!isSuccess(response)) {
			throw new UserException("Impossible de récupérer la liste des groupes du NAS");
		}

		return parseGroupList(response);
	}

	static List<SynoGroup> parseGroupList(final JsonNode response) {
		final List<SynoGroup> groups = new ArrayList<>();
		for (final JsonNode group : response.path("data").path("groups")) {
			groups.add(new SynoGroup(group.path("gid").asInt(), group.path("name").asText()));
		}
		return groups;
	}

	/**
	 * Liste les droits des dossiers racine et de leurs enfants directs.
	 */
	public List<SynoFolderPermission> getAccessRight() {
		final List<SynoFolderPermission> list = new ArrayList<>();

		final List<SynoFolderPermission> rootFolders = listFolderPermissions(null);
		list.addAll(rootFolders);

		for (final SynoFolderPermission rootFolder : rootFolders) {
			list.addAll(listFolderPermissions(rootFolder.id()));
		}

		return list;
	}

	/**
	 * Liste les dossiers Synology Photos directement sous le dossier donné (racine si null),
	 * avec les groupes autorisés.
	 */
	public List<SynoFolderPermission> listFolderPermissions(final Integer id) {
		LogUtil.debug("List folders for id " + id);

		final Map<String, String> params = new LinkedHashMap<>();
		params.put("api", "SYNO.FotoTeam.Browse.Folder");
		params.put("method", "list");
		params.put("version", "2");
		if (id != null) {
			params.put("id", id.toString());
		}
		params.put("offset", "0");
		params.put("limit", "5000");
		params.put("additional", "[\"sharing_info\"]");

		final JsonNode response = doRequest("/webapi/entry.cgi/SYNO.FotoTeam.Browse.Folder", params);

		if (!isSuccess(response)) {
			throw new UserException("Impossible de récupérer les droits");
		}

		return parseFolderList(response);
	}

	static List<SynoFolderPermission> parseFolderList(final JsonNode response) {
		final List<SynoFolderPermission> folders = new ArrayList<>();
		for (final JsonNode folder : response.path("data").path("list")) {
			final List<Integer> browseGrpIds = new ArrayList<>();
			for (final JsonNode permission : folder.path("additional").path("sharing_info").path("permission")) {
				browseGrpIds.add(permission.path("id").asInt());
			}
			folders.add(new SynoFolderPermission(folder.path("id").asInt(), folder.path("name").asText(), browseGrpIds));
		}
		return folders;
	}

	public void setAccessRight(final String gid, final List<Folder> foldersToAddList, final List<Folder> foldersToDeleteList) {
		final Map<String, String> params = new LinkedHashMap<>();
		params.put("api", "SYNO.FotoTeam.Sharing.FolderBatchPermission");
		params.put("version", "1");
		params.put("method", "update_by_member");
		params.put("permission", buildPermissionParam(gid, foldersToAddList, foldersToDeleteList));

		final JsonNode response = doRequest("/webapi/entry.cgi/SYNO.FotoTeam.Sharing.FolderBatchPermission", params);

		if (!isSuccess(response)) {
			throw new UserException("Impossible de setter les droits");
		}
	}

	// construit le JSON de mise à jour des droits d'un groupe : ajouts avec le rôle download, suppressions
	static String buildPermissionParam(final String gid, final List<Folder> foldersToAddList, final List<Folder> foldersToDeleteList) {
		final ArrayNode actions = MAPPER.createArrayNode();
		for (final Folder folder : foldersToAddList) {
			actions.addObject()
					.put("folder_id", folder.getId())
					.put("action", "update")
					.put("role", "download");
		}
		for (final Folder folder : foldersToDeleteList) {
			actions.addObject()
					.put("folder_id", folder.getId())
					.put("action", "delete");
		}

		final ObjectNode permission = MAPPER.createObjectNode();
		permission.put("id", Long.parseLong(gid));
		permission.put("type", "group");
		permission.set("list", actions);

		return permission.toString();
	}

	public void addAccessRight(final String gid, final Folder... foldersToAdd) {
		setAccessRight(gid, Arrays.asList(foldersToAdd), List.of());
	}

	public void removeAccessRight(final String gid, final Folder... folderToDelete) {
		setAccessRight(gid, List.of(), Arrays.asList(folderToDelete));
	}

	private JsonNode doRequest(final String path, final Map<String, String> params) {
		final String body = params.entrySet().stream()
				.map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
				.collect(Collectors.joining("&"));

		final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(pref.getUrl() + path))
				.timeout(Duration.ofSeconds(60))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body));

		if (cookieHeader != null) {
			builder.header("Cookie", cookieHeader);
		}
		if (synoToken != null) {
			builder.header("X-SYNO-TOKEN", synoToken);
		}

		try {
			final HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
			return MAPPER.readTree(response.body());
		} catch (final IOException e) {
			throw new UserException("Erreur de communication avec le NAS (" + pref.getUrl() + ") : " + e.getMessage());
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
	}

	private static String urlEncode(final String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
