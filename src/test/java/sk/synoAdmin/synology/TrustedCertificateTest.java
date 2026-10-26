package sk.synoAdmin.synology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sk.synoAdmin.FakeDsm;
import sk.synoAdmin.UserException;
import sk.synoAdmin.prefs.Session;

/**
 * NAS en HTTPS avec un certificat auto-signé (CN=nas, accédé par 127.0.0.1 : le nom ne
 * correspond pas non plus), comme un DSM avec son certificat par défaut.
 */
class TrustedCertificateTest {

	private static final char[] STORE_PASSWORD = "changeit".toCharArray();

	@TempDir
	static Path tempDir;

	private static FakeDsm dsm;
	private static X509Certificate serverCertificate;

	@BeforeAll
	static void startHttpsDsm() throws Exception {
		final Path keyStoreFile = tempDir.resolve("nas.p12");
		final String keytool = Paths.get(System.getProperty("java.home"), "bin",
				System.getProperty("os.name").toLowerCase().startsWith("windows") ? "keytool.exe" : "keytool").toString();
		final Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "nas", "-keyalg", "RSA", "-keysize", "2048",
				"-validity", "2", "-dname", "CN=nas", "-keystore", keyStoreFile.toString(), "-storetype", "PKCS12",
				"-storepass", "changeit", "-keypass", "changeit")
				.redirectErrorStream(true).start();
		final String output = new String(process.getInputStream().readAllBytes());
		assertEquals(0, process.waitFor(), output);

		final KeyStore keyStore = KeyStore.getInstance("PKCS12");
		try (InputStream input = Files.newInputStream(keyStoreFile)) {
			keyStore.load(input, STORE_PASSWORD);
		}
		serverCertificate = (X509Certificate) keyStore.getCertificate("nas");

		final KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keyManagers.init(keyStore, STORE_PASSWORD);
		final SSLContext tls = SSLContext.getInstance("TLS");
		tls.init(keyManagers.getKeyManagers(), null, null);
		dsm = new FakeDsm(tls);
	}

	@AfterAll
	static void stopDsm() {
		dsm.close();
	}

	private static SynoApiClient client(final String fingerprint) {
		return new SynoApiClient(dsm.url(), "admin", "pwd", false, Session.load(tempDir).transientCopy(false), fingerprint, null);
	}

	@Test
	void certificatAutoSigneNonAccepte_erreurExplicite() {
		final UserException e = assertThrows(UserException.class, () -> client(null).login());

		assertTrue(e.getMessage().contains("certificate"), e.getMessage());
		assertTrue(e.getMessage().contains("synoRight init"), e.getMessage());
	}

	@Test
	void fetchServerCertificate_renvoieLeCertificatPresente() {
		final X509Certificate fetched = TrustedCertificate.fetchServerCertificate(dsm.url());

		assertEquals(TrustedCertificate.fingerprint(serverCertificate), TrustedCertificate.fingerprint(fetched));
	}

	@Test
	void empreinteAcceptee_connexionOk() {
		client(TrustedCertificate.fingerprint(serverCertificate)).login();
	}

	@Test
	void empreinteAcceptee_insensibleALaCasse() {
		client(TrustedCertificate.fingerprint(serverCertificate).toLowerCase()).login();
	}

	@Test
	void autreEmpreinte_refusee() {
		final String real = TrustedCertificate.fingerprint(serverCertificate);
		// premier octet toujours différent du vrai (le certificat aléatoire peut commencer par 00)
		final String other = (real.startsWith("00") ? "01" : "00") + real.substring(2);

		assertThrows(UserException.class, () -> client(other).login());
	}

	@Test
	void fingerprint_formatSha256Hexa() {
		assertTrue(TrustedCertificate.fingerprint(serverCertificate).matches("([0-9A-F]{2}:){31}[0-9A-F]{2}"));
	}
}
