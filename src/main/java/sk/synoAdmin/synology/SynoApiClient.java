package sk.synoAdmin.synology;

import java.util.List;
import java.util.stream.Collectors;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.Response;

import sk.synoAdmin.UserException;
import sk.synoAdmin.engine.Folder;
import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.synology.pojo.SynoAccessRightResponse;
import sk.synoAdmin.synology.pojo.SynoLoginResponse;
import sk.synoAdmin.synology.pojo.SynoSetAccessRightResponse;

public class SynoApiClient {

	private final Preferences pref;
	private final WebTarget webTarget;
	private Cookie cookieSession;

	public SynoApiClient(final Preferences pref) {
		this.pref = pref;
		final Client client = ClientBuilder.newClient();
		webTarget = client.target(pref.getUrl());

		login();
	}

	private void login() {
		final long start = System.nanoTime();

		final Form form = new Form();
		form.param("api", "SYNO.PhotoStation.Auth");
		form.param("method", "photo_login");
		form.param("version", "1");
		form.param("username", pref.getUser());
		form.param("passwd", pref.getPassword());
//		form.param("enable_syno_token", "true");

		final Response response = doRequest("webapi/auth.php", form);
		final SynoLoginResponse synoLoginResponse = response.readEntity(SynoLoginResponse.class);

		if (synoLoginResponse == null || !synoLoginResponse.isSuccess()) {
			throw new UserException("Login : fail");
		}

		if (!synoLoginResponse.isAdmin()) {
			throw new UserException("Login : utilisteur non admin");
		}

		cookieSession = response.getCookies().get("PHPSESSID");

		final long end = System.nanoTime();
		System.out.println("Login ok : " + (end - start) / 1000000);
	}

	public SynoAccessRightResponse getAccessRight(final String gid) {

		final Form form = new Form();
		form.param("action", "get_access_right");
		form.param("gid", gid);
		form.param("isDsmAccount", "true");

		final Response resp = doRequest("admin/access_right.php", form);
		final SynoAccessRightResponse synoResponse = resp.readEntity(SynoAccessRightResponse.class);

		if (synoResponse == null) {
			throw new UserException("Impossible de récupérer les droits");
		}

		return synoResponse;
	}

	public SynoSetAccessRightResponse setAccessRight(final String gid, final List<Folder> groupsToAdd, final List<Folder> groupsToDelete) {
		final Form form = new Form();
		form.param("api", "SYNO.PhotoStation.Permission");
		form.param("version", "1");
		form.param("method", "editgroup");
		form.param("id", gid);
		form.param("album_browse_delete", groupsToDelete.stream()
				.map(Folder::getId)
				.collect(Collectors.joining(",")));
		form.param("album_browse_add", groupsToAdd.stream()
				.map(Folder::getId)
				.collect(Collectors.joining(",")));
//		form.param("album_upload_delete", "");
//		form.param("album_upload_add", "");
//		form.param("album_manage_delete", "");
//		form.param("album_manage_add", "");

		final Response resp = doRequest("webapi/permission.php", form);

		final SynoSetAccessRightResponse synoResponse = resp.readEntity(SynoSetAccessRightResponse.class);

		if (synoResponse == null || !synoResponse.isSuccess()) {
			throw new UserException("Impossible de setter les droits");
		}

		return synoResponse;
	}

	private Response doRequest(final String path, final Form form) {
		final Builder builder = webTarget
				.path(path)
				.request();

		if (cookieSession != null) {
			builder.cookie(cookieSession);
		}

		final Response response = builder.post(Entity.form(form));

		response.getHeaders().putSingle("Content-type", "text/json"); // mauvais Content-type retourné par l'API, on surcharge
		return response;
	}
}
