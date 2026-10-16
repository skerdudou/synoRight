package sk.synoAdmin.synology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Path;

import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sk.synoAdmin.FakeDsm;
import sk.synoAdmin.UserException;
import sk.synoAdmin.prefs.Session;

class ConnectionCheckTest {

	static {
		if (System.getProperty("synoRight.keyFile") == null) {
			System.setProperty("synoRight.keyFile", System.getProperty("java.io.tmpdir") + "/synoRight-test.key");
		}
	}

	@Test
	void normalizeUrl_ajouteHttpsEtRetireLeSlash() {
		assertEquals("https://nas:5001", ConnectionCheck.normalizeUrl("nas:5001/"));
		assertEquals("http://nas:5000", ConnectionCheck.normalizeUrl(" http://nas:5000 "));
		assertEquals("HTTPS://nas", ConnectionCheck.normalizeUrl("HTTPS://nas"));
	}

	@Test
	void isCertificateError_causeSslDansLaChaine() {
		assertTrue(ConnectionCheck.isCertificateError(new UserException("x", new IOException(new SSLHandshakeException("PKIX")))));
		assertFalse(ConnectionCheck.isCertificateError(new UserException("x", new ConnectException())));
	}

	@Test
	void connect_sessionDeTravailSeparee(@TempDir final Path dir) throws IOException {
		try (FakeDsm dsm = new FakeDsm()) {
			final Session session = Session.load(dir);

			final ConnectionCheck.Result result = ConnectionCheck.connect(dsm.url(), "admin", "pwd", false, session, false, null);

			assertEquals("pwd", result.password());
			assertNull(session.getCookieSession()); // session mémorisée inchangée
			assertEquals("sid", result.workSession().getCookieSession());
		}
	}
}
