package sk.synoAdmin.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import sk.synoAdmin.UserException;

class UpdaterTest {

	private static final byte[] SETUP = "contenu de l'installeur".getBytes(StandardCharsets.UTF_8);
	private static final String FILE = "synoRight-0.5-setup.exe";

	// libère les réponses bloquées en fin de test
	private final CountDownLatch release = new CountDownLatch(1);
	private final AtomicInteger hits = new AtomicInteger();
	private HttpServer server;
	private String base;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/setup.exe", exchange -> {
			hits.incrementAndGet();
			exchange.sendResponseHeaders(200, SETUP.length);
			exchange.getResponseBody().write(SETUP);
			exchange.close();
		});
		// en-têtes et début du fichier, puis plus rien
		server.createContext("/stall", exchange -> {
			hits.incrementAndGet();
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
			hits.incrementAndGet();
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

	private Release.Asset asset(final String path, final long size, final Optional<String> sha) {
		return new Release.Asset(base + path, size, sha);
	}

	private static Path download(final Release.Asset asset, final Path dir) {
		return Updater.download(asset, FILE, dir, l -> {
			// rien
		}, p -> {
			// rien
		}, () -> false);
	}

	private static Release releaseWith(final Optional<Release.Asset> setup, final Optional<Release.Asset> zip) {
		return new Release("0.5", "u", setup, zip);
	}

	@Test
	void modeInstallAvecDesinstalleurEtSetup(@TempDir final Path dir) throws IOException {
		Files.createFile(dir.resolve("unins000.exe"));
		final Release.Asset any = new Release.Asset("s", 1, Optional.of("00"));
		assertEquals(Updater.Mode.INSTALL, Updater.mode(releaseWith(Optional.of(any), Optional.empty()), Optional.of(dir)));
		assertEquals(Updater.Mode.OPEN_PAGE, Updater.mode(releaseWith(Optional.empty(), Optional.of(any)), Optional.of(dir)));
	}

	@Test
	void modePortableSansDesinstalleurAvecExeEtZip(@TempDir final Path dir) throws IOException {
		Files.createFile(dir.resolve("synoRightGui.exe"));
		final Release.Asset any = new Release.Asset("z", 1, Optional.of("00"));
		assertEquals(Updater.Mode.PORTABLE, Updater.mode(releaseWith(Optional.empty(), Optional.of(any)), Optional.of(dir)));
		assertEquals(Updater.Mode.OPEN_PAGE, Updater.mode(releaseWith(Optional.of(any), Optional.empty()), Optional.of(dir)));
	}

	@Test
	void modeOpenPageSansExeOuSansDossier(@TempDir final Path dir) {
		final Release.Asset any = new Release.Asset("z", 1, Optional.of("00"));
		final Release both = releaseWith(Optional.of(any), Optional.of(any));
		assertEquals(Updater.Mode.OPEN_PAGE, Updater.mode(both, Optional.of(dir)));
		assertEquals(Updater.Mode.OPEN_PAGE, Updater.mode(both, Optional.empty()));
	}

	@Test
	void setupCommand() {
		final Path setup = Path.of("C:\\Temp\\synoRight-0.5-setup.exe");
		assertEquals(List.of(setup.toString(), "/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS", "/RELAUNCH"),
				Updater.setupCommand(setup));
	}

	@Test
	void downloadBonneEmpreinteEtTaille(@TempDir final Path dir) throws Exception {
		final AtomicLong progress = new AtomicLong();
		// empreinte en majuscules : comparaison sans casse
		final Path file = Updater.download(asset("/setup.exe", SETUP.length, Optional.of(sha256(SETUP).toUpperCase())), FILE, dir,
				l -> {
					// rien
				}, progress::set, () -> false);
		assertEquals(dir.resolve(FILE), file);
		assertArrayEquals(SETUP, Files.readAllBytes(file));
		assertEquals(SETUP.length, progress.get());
	}

	@Test
	void downloadSansEmpreinte_refuseSansAppelAuServeur(@TempDir final Path dir) {
		final UserException e = assertThrows(UserException.class,
				() -> download(asset("/setup.exe", SETUP.length, Optional.empty()), dir));
		assertEquals("The update file has no published checksum: download refused.", e.getMessage());
		assertEquals(0, hits.get());
		assertFalse(Files.exists(dir.resolve(FILE)));
	}

	@Test
	void downloadMauvaiseTaille_fichierSupprime(@TempDir final Path dir) throws Exception {
		final String sha = sha256(SETUP);
		final UserException e = assertThrows(UserException.class,
				() -> download(asset("/setup.exe", SETUP.length + 5, Optional.of(sha)), dir));
		assertEquals("The downloaded file does not have the expected size (" + SETUP.length + " bytes instead of "
				+ (SETUP.length + 5) + ").", e.getMessage());
		assertFalse(Files.exists(dir.resolve(FILE)));
	}

	@Test
	void downloadMauvaiseEmpreinte_fichierSupprime(@TempDir final Path dir) {
		final UserException e = assertThrows(UserException.class,
				() -> download(asset("/setup.exe", SETUP.length, Optional.of("00ff")), dir));
		assertEquals("The downloaded file is corrupted (checksum mismatch). Please try again.", e.getMessage());
		assertFalse(Files.exists(dir.resolve(FILE)));
	}

	@Test
	void downloadErreurHttp(@TempDir final Path dir) throws IOException {
		final UserException e = assertThrows(UserException.class,
				() -> download(asset("/missing", 1, Optional.of("00")), dir));
		assertEquals("Could not download the update (HTTP 404).", e.getMessage());
		try (var files = Files.list(dir)) {
			assertEquals(0, files.count());
		}
	}

	@Test
	void downloadBloque_echoueApresLeDelai(@TempDir final Path dir) {
		final UserException e = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
			final UserException thrown = assertThrows(UserException.class,
					() -> Updater.download(asset("/stall", 1000, Optional.of("00")), FILE, dir, l -> {
						// rien
					}, p -> {
						// rien
					}, () -> false, Duration.ofSeconds(2), Duration.ofMillis(500)));
			// l'interruption interne au téléchargement ne doit pas rester sur le thread appelant
			assertFalse(Thread.currentThread().isInterrupted());
			return thrown;
		});
		assertEquals("The download stopped responding. Please try again.", e.getMessage());
		assertFalse(Files.exists(dir.resolve(FILE)));
	}

	@Test
	void downloadAnnule_fichierSupprime(@TempDir final Path dir) {
		final AtomicBoolean cancelled = new AtomicBoolean();
		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(Updater.DownloadCancelledException.class,
				() -> Updater.download(asset("/stall", 1000, Optional.of("00")), FILE, dir, l -> {
					// rien
				}, p -> cancelled.set(true), cancelled::get)));
		assertFalse(Files.exists(dir.resolve(FILE)));
	}
}
