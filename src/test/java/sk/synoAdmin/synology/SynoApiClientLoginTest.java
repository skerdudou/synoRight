package sk.synoAdmin.synology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import sk.synoAdmin.ConsoleUtil;
import sk.synoAdmin.UserException;
import sk.synoAdmin.engine.Group;
import sk.synoAdmin.engine.RightEngine;
import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.prefs.Session;

/**
 * Login contre un faux DSM : mot de passe "good", OTP "123456" si activé, session "valid".
 */
class SynoApiClientLoginTest {

	static {
		// ne pas toucher la clé réelle de l'utilisateur quand les tests tournent hors de maven
		if (System.getProperty("synoRight.keyFile") == null) {
			System.setProperty("synoRight.keyFile", System.getProperty("java.io.tmpdir") + "/synoRight-test.key");
		}
	}

	@TempDir
	Path tempDir;

	private HttpServer server;
	private String url;
	private boolean otpRequired;
	private int loginErrorCode = 400;
	private final AtomicInteger authCalls = new AtomicInteger();
	private final PrintStream originalOut = System.out;
	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.start();
		url = "http://127.0.0.1:" + server.getAddress().getPort();
		System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
	}

	@AfterEach
	void stopServer() {
		System.setOut(originalOut);
		ConsoleUtil.setInput(System.in);
		server.stop(0);
	}

	private void handle(final HttpExchange exchange) throws IOException {
		final Map<String, String> params = parseForm(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		final String cookie = exchange.getRequestHeaders().getFirst("Cookie");

		final String body;
		if ("SYNO.Core.Desktop.Timeout".equals(params.get("api"))) {
			body = cookie != null && cookie.contains("id=valid") ? "{\"success\":true}" : "{\"success\":false,\"error\":{\"code\":119}}";
		} else if ("SYNO.API.Auth".equals(params.get("api"))) {
			authCalls.incrementAndGet();
			if (!"good".equals(params.get("passwd"))) {
				body = "{\"success\":false,\"error\":{\"code\":" + loginErrorCode + "}}";
			} else if (otpRequired && params.get("otp_code") == null) {
				body = "{\"success\":false,\"error\":{\"code\":403}}";
			} else if (otpRequired && !"123456".equals(params.get("otp_code"))) {
				body = "{\"success\":false,\"error\":{\"code\":404}}";
			} else {
				body = "{\"success\":true,\"data\":{\"sid\":\"valid\",\"synotoken\":\"tok\""
						+ (otpRequired ? ",\"device_id\":\"dev1\"" : "") + "}}";
			}
		} else {
			body = "{\"success\":false,\"error\":{\"code\":102}}";
		}

		final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(200, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	private static Map<String, String> parseForm(final String body) {
		final Map<String, String> params = new HashMap<>();
		for (final String pair : body.split("&")) {
			final String[] kv = pair.split("=", 2);
			if (kv.length == 2) {
				params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
			}
		}
		return params;
	}

	private static void typed(final String... lines) {
		ConsoleUtil.setInput(new ByteArrayInputStream((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8)));
	}

	private String output() {
		return out.toString(StandardCharsets.UTF_8);
	}

	@Test
	void motDePasseMemoriseRefuse_ressaisi_callbackAppeleEtSessionMemorisee() {
		final Session session = Session.load(tempDir);
		final List<String> corrected = new ArrayList<>();
		typed("good");

		new SynoApiClient(url, "admin", "old", true, session, corrected::add).login();

		assertEquals(List.of("good"), corrected);
		assertEquals("valid", Session.load(tempDir).getCookieSession());
		assertTrue(output().contains("mot de passe mémorisé a été refusé"));
	}

	@Test
	void motDePasseSaisi_faute_messageSansParlerDeMemorise() {
		typed("good");

		new SynoApiClient(url, "admin", "typo", false, Session.load(tempDir).transientCopy(false), null).login();

		assertTrue(output().contains("Identifiant ou mot de passe incorrect."));
		assertFalse(output().contains("mémorisé"));
	}

	@Test
	void troisMauvaisMotsDePasse_userException() {
		typed("bad2", "bad3");
		final SynoApiClient client = new SynoApiClient(url, "admin", "bad1", false, Session.load(tempDir), null);

		final UserException e = assertThrows(UserException.class, client::login);

		assertTrue(e.getMessage().contains("mot de passe incorrect"));
		assertEquals(3, authCalls.get());
		assertNull(Session.load(tempDir).getCookieSession());
	}

	@Test
	void otp_codeFauxPuisBon_deviceIdMemorise() {
		otpRequired = true;
		final Session session = Session.load(tempDir);
		typed("000000", "123456");

		new SynoApiClient(url, "admin", "good", true, session, null).login();

		assertTrue(output().contains("Code OTP incorrect."));
		assertEquals("dev1", Session.load(tempDir).getCookieDid());
	}

	@Test
	void sessionValide_pasDeNouveauLogin() {
		final Session session = Session.load(tempDir);
		session.update("valid", "tok", null);

		new SynoApiClient(url, "admin", null, false, session, null).login();

		assertEquals(0, authCalls.get());
	}

	@Test
	void sessionDeTravail_neModifiePasLaSessionMemorisee() {
		final Session session = Session.load(tempDir);
		session.update("old-sid", "old-tok", "dev0");
		final Session work = session.transientCopy(true);

		new SynoApiClient(url, "admin", "good", false, work, null).login();

		assertEquals("old-sid", Session.load(tempDir).getCookieSession());
		session.replaceWith(work);
		assertEquals("valid", Session.load(tempDir).getCookieSession());
		assertEquals("dev0", Session.load(tempDir).getCookieDid());
	}

	@Test
	void getFullState_echecDuLoginAsynchrone_userExceptionSansEnveloppe() {
		loginErrorCode = 401;
		final Preferences pref = new Preferences(tempDir, tempDir.toString(), url, "admin", "bad",
				List.of(new Group("3", "famille")), false);

		final UserException e = assertThrows(UserException.class, () -> new RightEngine(pref).getFullState());

		assertTrue(e.getMessage().contains("compte désactivé"));
	}
}
