package sk.synoAdmin.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import sk.synoAdmin.UserException;

class UpdaterTest {

	private static final byte[] SETUP = "contenu de l'installeur".getBytes(StandardCharsets.UTF_8);

	// libère les réponses bloquées en fin de test
	private final CountDownLatch release = new CountDownLatch(1);
	private HttpServer server;
	private String base;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/setup.exe", exchange -> {
			exchange.sendResponseHeaders(200, SETUP.length);
			exchange.getResponseBody().write(SETUP);
			exchange.close();
		});
		// en-têtes et début du fichier, puis plus rien
		server.createContext("/stall", exchange -> {
			exchange.sendResponseHeaders(200, 1000);
			exchange.getResponseBody().write(new byte[10]);
			exchange.getResponseBody().flush();
			try {
				release.await(10, TimeUnit.SECONDS);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			exchange.close();
		});
		server.createContext("/missing", exchange -> {
			exchange.sendResponseHeaders(404, -1);
			exchange.close();
		});
		server.setExecutor(Executors.newCachedThreadPool());
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void stopServer() {
		release.countDown();
		server.stop(0);
	}

	private static String sha256(final byte[] data) throws NoSuchAlgorithmException {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
	}

	private Release release(final String path, final Optional<String> sha) {
		return new Release("0.5", "https://github.com/skerdudou/synoRight/releases/tag/v0.5", Optional.of(base + path), sha);
	}

	@Test
	void modeInstallSeulementAvecSetupEtAsset() {
		final Release withSetup = new Release("0.5", "u", Optional.of("s"), Optional.empty());
		final Release withoutSetup = new Release("0.5", "u", Optional.empty(), Optional.empty());
		assertEquals(Updater.Mode.INSTALL, Updater.mode(withSetup, true));
		assertEquals(Updater.Mode.OPEN_PAGE, Updater.mode(withoutSetup, true));
		assertEquals(Updater.Mode.OPEN_PAGE, Updater.mode(withSetup, false));
	}

	@Test
	void setupCommand() {
		final Path setup = Path.of("C:\\Temp\\synoRight-0.5-setup.exe");
		assertEquals(List.of(setup.toString(), "/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS", "/RELAUNCH"),
				Updater.setupCommand(setup));
	}

	@Test
	void downloadBonneEmpreinte(@TempDir final Path dir) throws Exception {
		final AtomicLong progress = new AtomicLong();
		// empreinte en majuscules : comparaison sans casse
		final Path file = Updater.download(release("/setup.exe", Optional.of(sha256(SETUP).toUpperCase())), dir, progress::set);
		assertEquals(dir.resolve("synoRight-0.5-setup.exe"), file);
		assertArrayEquals(SETUP, Files.readAllBytes(file));
		assertEquals(SETUP.length, progress.get());
	}

	@Test
	void downloadSansEmpreinte(@TempDir final Path dir) throws IOException {
		final Path file = Updater.download(release("/setup.exe", Optional.empty()), dir, p -> {
			// rien
		});
		assertArrayEquals(SETUP, Files.readAllBytes(file));
	}

	@Test
	void downloadMauvaiseEmpreinte_fichierSupprime(@TempDir final Path dir) {
		final UserException e = assertThrows(UserException.class,
				() -> Updater.download(release("/setup.exe", Optional.of("00ff")), dir, p -> {
					// rien
				}));
		assertEquals("The downloaded file is corrupted (checksum mismatch). Please try again.", e.getMessage());
		assertFalse(Files.exists(dir.resolve("synoRight-0.5-setup.exe")));
	}

	@Test
	void downloadErreurHttp(@TempDir final Path dir) throws IOException {
		final UserException e = assertThrows(UserException.class,
				() -> Updater.download(release("/missing", Optional.empty()), dir, p -> {
					// rien
				}));
		assertEquals("Could not download the update (HTTP 404).", e.getMessage());
		try (var files = Files.list(dir)) {
			assertEquals(0, files.count());
		}
	}

	@Test
	void downloadBloque_echoueApresLeDelai(@TempDir final Path dir) {
		final UserException e = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
			final UserException thrown = assertThrows(UserException.class,
					() -> Updater.download(release("/stall", Optional.empty()), dir, l -> {
						// rien
					}, p -> {
						// rien
					}, () -> false, Duration.ofSeconds(2), Duration.ofMillis(500)));
			// l'interruption interne au téléchargement ne doit pas rester sur le thread appelant
			assertFalse(Thread.currentThread().isInterrupted());
			return thrown;
		});
		assertEquals("The download stopped responding. Please try again.", e.getMessage());
		assertFalse(Files.exists(dir.resolve("synoRight-0.5-setup.exe")));
	}

	@Test
	void downloadAnnule_fichierSupprime(@TempDir final Path dir) {
		final AtomicBoolean cancelled = new AtomicBoolean();
		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(Updater.DownloadCancelledException.class,
				() -> Updater.download(release("/stall", Optional.empty()), dir, l -> {
					// rien
				}, p -> cancelled.set(true), cancelled::get)));
		assertFalse(Files.exists(dir.resolve("synoRight-0.5-setup.exe")));
	}
}
