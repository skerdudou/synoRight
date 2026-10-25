package sk.synoAdmin.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelectionCollectorTest {

	private static final Duration QUIET = Duration.ofMillis(800);
	private static final Duration MAX = Duration.ofSeconds(10);

	@TempDir
	Path dir;

	private Path portFile(final String key) {
		return dir.resolve("synoRight-" + SelectionCollector.hash(key) + ".collect");
	}

	@Test
	void seul_traiteSesDossiersEtNettoie() {
		final Optional<List<String>> result = SelectionCollector.collect(dir, "rights", List.of("P:\\a"), QUIET, MAX);

		assertEquals(Optional.of(List.of("P:\\a")), result);
		assertFalse(Files.exists(portFile("rights")));
	}

	@Test
	void plusieursProcessus_unSeulTraiteToutLaSelection() throws Exception {
		final List<String> folders = List.of("P:\\a", "P:\\b", "P:\\c", "P:\\d");
		final CyclicBarrier start = new CyclicBarrier(folders.size());
		final List<CompletableFuture<Optional<List<String>>>> results = new ArrayList<>();
		for (final String folder : folders) {
			results.add(CompletableFuture.supplyAsync(() -> {
				try {
					start.await();
				} catch (final Exception e) {
					throw new IllegalStateException(e);
				}
				return SelectionCollector.collect(dir, "rights", List.of(folder), QUIET, MAX);
			}));
		}

		final List<List<String>> handled = new ArrayList<>();
		for (final CompletableFuture<Optional<List<String>>> result : results) {
			result.get(30, TimeUnit.SECONDS).ifPresent(handled::add);
		}
		assertEquals(1, handled.size());
		assertEquals(folders.stream().sorted().toList(), handled.getFirst().stream().sorted().toList());
	}

	@Test
	void actionsDifferentes_nonRegroupees() throws Exception {
		final CompletableFuture<Optional<List<String>>> grant = CompletableFuture
				.supplyAsync(() -> SelectionCollector.collect(dir, "grant|famille", List.of("P:\\a"), QUIET, MAX));
		final Optional<List<String>> revoke = SelectionCollector.collect(dir, "revoke|famille", List.of("P:\\b"), QUIET, MAX);

		assertEquals(Optional.of(List.of("P:\\b")), revoke);
		assertEquals(Optional.of(List.of("P:\\a")), grant.get(30, TimeUnit.SECONDS));
	}

	@Test
	void fichierObsolete_collecteurDisparu_reprisParLeSuivant() throws IOException {
		final int closedPort;
		try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			closedPort = server.getLocalPort();
		}
		Files.writeString(portFile("rights"), closedPort + " jeton", StandardCharsets.UTF_8);

		assertEquals(Optional.of(List.of("P:\\a")), SelectionCollector.collect(dir, "rights", List.of("P:\\a"), QUIET, MAX));
	}

	@Test
	void mauvaisJeton_ignore() throws Exception {
		final CompletableFuture<Optional<List<String>>> collector = CompletableFuture
				.supplyAsync(() -> SelectionCollector.collect(dir, "rights", List.of("P:\\a"), Duration.ofSeconds(2), MAX));
		final Path portFile = portFile("rights");
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!Files.exists(portFile) && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		final int port = Integer.parseInt(Files.readString(portFile).split(" ")[0]);
		try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
			final Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
			out.write("faux\nP:\\intrus\n");
			out.flush();
			socket.shutdownOutput();
		}

		final List<String> result = collector.get(30, TimeUnit.SECONDS).orElseThrow();
		assertEquals(List.of("P:\\a"), result);
		assertTrue(Files.notExists(portFile));
	}
}
