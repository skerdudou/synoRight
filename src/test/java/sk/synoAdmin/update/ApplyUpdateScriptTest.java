package sk.synoAdmin.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Script de remplacement exécuté pour de vrai, sur des dossiers temporaires, sans relance. */
@EnabledOnOs(OS.WINDOWS)
class ApplyUpdateScriptTest {

	@TempDir
	Path tmp;

	private Path appDir;
	private Path logDir;

	// ancienne version installée, avec les fichiers de l'utilisateur à côté de l'exe
	private void oldVersion() throws IOException {
		appDir = Files.createDirectories(tmp.resolve("my app"));
		Files.createDirectories(appDir.resolve("app"));
		Files.createDirectories(appDir.resolve("runtime"));
		Files.writeString(appDir.resolve("app/old.jar"), "old jar");
		Files.writeString(appDir.resolve("runtime/x"), "old runtime");
		Files.writeString(appDir.resolve("synoRightGui.exe"), "old");
		Files.writeString(appDir.resolve("config.synoRight.properties"), "nas=x");
		Files.writeString(appDir.resolve("synoRightMenu-0.2.dll"), "old dll");
		logDir = tmp.resolve("logs");
	}

	// nouvelle version décompressée dans newDir
	private static Path newVersion(final Path newDir) throws IOException {
		Files.createDirectories(newDir.resolve("app"));
		Files.createDirectories(newDir.resolve("runtime"));
		Files.writeString(newDir.resolve("app/new.jar"), "new jar");
		Files.writeString(newDir.resolve("runtime/y"), "new runtime");
		Files.writeString(newDir.resolve("synoRightGui.exe"), "new version");
		Files.writeString(newDir.resolve("synoRightMenu-0.3.dll"), "new dll");
		return newDir;
	}

	// pid d'un processus déjà terminé
	private static long deadPid() throws IOException, InterruptedException {
		final Process process = new ProcessBuilder("cmd", "/c", "exit").start();
		assertTrue(process.waitFor(10, TimeUnit.SECONDS));
		return process.pid();
	}

	private void run(final Path script, final Path newDir) throws IOException, InterruptedException {
		final List<String> command = new ArrayList<>(PortableUpdate.scriptCommand(script, deadPid(), appDir, newDir,
				appDir.resolve("synoRightGui.exe"), logDir));
		command.add("-NoRelaunch");
		final Process process = new ProcessBuilder(command).redirectErrorStream(true)
				.redirectOutput(tmp.resolve("script-output.txt").toFile()).start();
		assertTrue(process.waitFor(60, TimeUnit.SECONDS), "script still running");
	}

	private static List<String> names(final Path dir) throws IOException {
		try (Stream<Path> files = Files.list(dir)) {
			return files.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	private void assertReplaced() throws IOException {
		assertEquals(List.of("new.jar"), names(appDir.resolve("app")));
		assertEquals(List.of("y"), names(appDir.resolve("runtime")));
		assertEquals("new version", Files.readString(appDir.resolve("synoRightGui.exe")));
		assertEquals("new dll", Files.readString(appDir.resolve("synoRightMenu-0.3.dll")));
		// fichiers de l'utilisateur et ancienne DLL du menu conservés
		assertEquals("nas=x", Files.readString(appDir.resolve("config.synoRight.properties")));
		assertEquals("old dll", Files.readString(appDir.resolve("synoRightMenu-0.2.dll")));
	}

	private String log() throws IOException {
		return Files.readString(logDir.resolve("update.log"), StandardCharsets.UTF_8);
	}

	@Test
	void replacesTheAppImageKeepsUserFilesAndRemovesTheTemporaryFolder() throws Exception {
		oldVersion();
		final Path temp = Files.createDirectories(tmp.resolve("synoRight-update-1"));
		final Path script = PortableUpdate.copyScript(temp);
		final Path newDir = newVersion(temp.resolve("synoRight"));

		run(script, newDir);

		assertReplaced();
		final String log = log();
		assertTrue(log.contains("robocopy app"), log);
		assertTrue(log.contains("robocopy runtime"), log);
		assertTrue(log.contains("Update finished"), log);
		// suppression éventuellement différée (le script tournait depuis ce dossier)
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (Files.exists(temp) && System.nanoTime() < deadline) {
			Thread.sleep(200);
		}
		assertFalse(Files.exists(temp), log);
	}

	@Test
	void neverDeletesAFolderContainingTheApp() throws Exception {
		oldVersion();
		final Path scripts = Files.createDirectories(tmp.resolve("scripts"));
		final Path script = PortableUpdate.copyScript(scripts);
		// le dossier « temporaire » (parent de newDir) serait le parent du dossier de l'appli
		final Path newDir = newVersion(tmp.resolve("synoRight"));

		run(script, newDir);

		assertReplaced();
		assertTrue(Files.exists(newDir.resolve("synoRightGui.exe")));
		assertTrue(Files.exists(script));
		assertTrue(log().contains("Refusing to delete"), log());
	}
}
