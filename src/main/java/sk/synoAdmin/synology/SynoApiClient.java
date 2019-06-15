package sk.synoAdmin.synology;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.Response;

import sk.synoAdmin.UserException;
import sk.synoAdmin.synology.pojo.SynoAccessRightResponse;
import sk.synoAdmin.synology.pojo.SynoLoginResponse;

public class SynoApiClient {

	private final WebTarget webTarget;
	private boolean isLogged = false;
	private Cookie cookieSession;

	public SynoApiClient() {
		final Client client = ClientBuilder.newClient();
		webTarget = client.target("http://192.168.0.15/photo");
	}

	public void login(final String user, final String passwd) {
		final Form form = new Form();
		form.param("api", "SYNO.PhotoStation.Auth");
		form.param("method", "photo_login");
		form.param("version", "1");
		form.param("username", user);
		form.param("passwd", passwd);
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
		isLogged = true;
		System.out.println("Login : ok");
	}

	public SynoAccessRightResponse getAccessRight(final String gid) {
		if (!isLogged) {
			throw new RuntimeException("Il faut se connecter avant");
		}

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
