package sk.synoAdmin.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sk.synoAdmin.UserException;

class PortableUpdateTest {

	@TempDir
	Path tmp;

	// zip dont les entrées (nom -> contenu, null pour un dossier) sont écrites dans l'ordre
	private Path zip(final String... namesAndContents) throws IOException {
		final Path zip = tmp.resolve("update.zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			for (int i = 0; i < namesAndContents.length; i += 2) {
				out.putNextEntry(new ZipEntry(namesAndContents[i]));
				if (namesAndContents[i + 1] != null) {
					out.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
				}
				out.closeEntry();
			}
		}
		return zip;
	}

	@Test
	void extractReturnsTheSynoRightFolder() throws IOException {
		final Path zip = zip("synoRight/", null, "synoRight/synoRightGui.exe", "exe", "synoRight/app/synoRight.jar", "jar",
				"synoRight/runtime/bin/java.dll", "dll");
		final Path target = Files.createDirectory(tmp.resolve("out"));

		final Path dir = PortableUpdate.extract(zip, target);

		assertEquals(target.resolve("synoRight"), dir);
		assertEquals("exe", Files.readString(dir.resolve("synoRightGui.exe")));
		assertEquals("jar", Files.readString(dir.resolve("app/synoRight.jar")));
		assertEquals("dll", Files.readString(dir.resolve("runtime/bin/java.dll")));
	}

	@Test
	void extractRefusesAnEntryGoingOutOfTheTarget() throws IOException {
		final Path zip = zip("synoRight/synoRightGui.exe", "exe", "../evil.txt", "evil", "synoRight/../../evil2.txt", "evil");
		final Path target = Files.createDirectory(tmp.resolve("out"));

		assertThrows(UserException.class, () -> PortableUpdate.extract(zip, target));
		assertFalse(Files.exists(tmp.resolve("evil.txt")));
		assertFalse(Files.exists(tmp.getParent().resolve("evil2.txt")));
	}

	@Test
	void extractRefusesAnAbsoluteEntry() throws IOException {
		final Path outside = tmp.resolve("abs.txt").toAbsolutePath();
		final Path zip = zip("synoRight/synoRightGui.exe", "exe", outside.toString().replace('\\', '/'), "evil");
		final Path target = Files.createDirectory(tmp.resolve("out"));

		assertThrows(UserException.class, () -> PortableUpdate.extract(zip, target));
		assertFalse(Files.exists(outside));
	}

	@Test
	void extractRefusesARootedEntry() throws IOException {
		final Path zip = zip("synoRight/synoRightGui.exe", "exe", "/rooted.txt", "evil");
		final Path target = Files.createDirectory(tmp.resolve("out"));

		assertThrows(UserException.class, () -> PortableUpdate.extract(zip, target));
	}

	@Test
	void extractRefusesAZipWithoutSynoRightFolder() throws IOException {
		final Path zip = zip("synoRightGui.exe", "exe", "app/synoRight.jar", "jar");
		final Path target = Files.createDirectory(tmp.resolve("out"));

		assertThrows(UserException.class, () -> PortableUpdate.extract(zip, target));
	}

	@Test
	void extractRefusesAZipWithoutTheExe() throws IOException {
		final Path zip = zip("synoRight/app/synoRight.jar", "jar");
		final Path target = Files.createDirectory(tmp.resolve("out"));

		assertThrows(UserException.class, () -> PortableUpdate.extract(zip, target));
	}

	@Test
	void extractRefusesAZipWithoutApp() throws IOException {
		final Path zip = zip("synoRight/synoRightGui.exe", "exe", "synoRight/app/", null, "synoRight/runtime/bin/java.dll", "dll");
		final Path target = Files.createDirectory(tmp.resolve("out"));

		assertThrows(UserException.class, () -> PortableUpdate.extract(zip, target));
	}

	@Test
	void extractRefusesAZipWithoutRuntime() throws IOException {
		final Path zip = zip("synoRight/synoRightGui.exe", "exe", "synoRight/app/synoRight.jar", "jar");
		final Path target = Files.createDirectory(tmp.resolve("out"));

		assertThrows(UserException.class, () -> PortableUpdate.extract(zip, target));
	}

	@Test
	void badZipMessageWithoutExceptionMessage() {
		final UserException e = PortableUpdate.badZip(new java.util.zip.ZipException());
		assertTrue(e.getMessage().contains("ZipException"), e.getMessage());
		assertFalse(e.getMessage().contains("null"), e.getMessage());
	}

	@Test
	void startRefusesANewFolderOutsideTheTemporaryFolder() throws IOException {
		final Path temp = Files.createDirectory(tmp.resolve("synoRight-update-1"));
		final Path newDir = Files.createDirectories(tmp.resolve("elsewhere/synoRight"));

		assertThrows(IllegalArgumentException.class,
				() -> PortableUpdate.start(temp, newDir, tmp.resolve("app"), tmp.resolve("logs"), tmp.resolve("config")));
		assertFalse(Files.exists(temp.resolve("apply-update.ps1")));
	}

	@Test
	void extractRefusesACorruptedZip() throws IOException {
		final Path zip = Files.writeString(tmp.resolve("update.zip"), "not a zip");
		final Path target = Files.createDirectory(tmp.resolve("out"));

		assertThrows(UserException.class, () -> PortableUpdate.extract(zip, target));
	}

	@Test
	void otherInstancesFindsProcessesRunningFromTheAppFolder() {
		final Path appDir = tmp.resolve("synoRight");
		final String gui = appDir.resolve("synoRightGui.exe").toString();
		final Map<Long, Optional<String>> processes = Map.of(1L, Optional.of(gui), // soi-même
				2L, Optional.of(appDir.resolve("synoRight.exe").toString()), // autre instance
				3L, Optional.of(tmp.resolve("other/synoRightGui.exe").toString()), // autre dossier
				4L, Optional.of(tmp.resolve("synoRight2/synoRightGui.exe").toString()), // préfixe trompeur
				5L, Optional.of(gui.toUpperCase(Locale.ROOT)), // casse différente
				6L, Optional.empty()); // commande inconnue (processus d'un autre utilisateur)
		final Stream<ProcessHandle> handles = processes.entrySet().stream()
				.map(e -> (ProcessHandle) new FakeProcess(e.getKey(), e.getValue()));

		final List<Long> found = PortableUpdate.otherInstances(appDir, 1, handles).stream().map(ProcessHandle::pid).sorted()
				.toList();

		assertEquals(List.of(2L, 5L), found);
	}

	@Test
	void otherInstancesCountsASynoRightProcessWhoseCommandIsHiddenButCommandLineKnown() {
		final Path appDir = tmp.resolve("app");
		final Stream<ProcessHandle> handles = Stream.of(
				new FakeProcess(2, Optional.empty(), Optional.of("SYNORIGHTGUI.EXE -c x")), // dossier invérifiable
				new FakeProcess(3, Optional.empty(), Optional.of("notepad.exe")), // autre programme
				new FakeProcess(4, Optional.empty(), Optional.of(" ")), // ligne vide
				new FakeProcess(5, Optional.of(tmp.resolve("other/synoRight.exe").toString()),
						Optional.of("synoRight.exe"))); // commande connue, autre dossier : la ligne n'est pas consultée

		final List<Long> found = PortableUpdate.otherInstances(appDir, 1, handles).stream().map(ProcessHandle::pid).toList();

		assertEquals(List.of(2L), found);
	}

	@Test
	void isWritableIsTrueForAWritableFolderAndLeavesNothingBehind() throws IOException {
		final Path dir = Files.createDirectory(tmp.resolve("app"));

		assertTrue(PortableUpdate.isWritable(dir));
		try (Stream<Path> files = Files.list(dir)) {
			assertEquals(0, files.count());
		}
	}

	@Test
	void isWritableIsFalseForAMissingFolder() {
		assertFalse(PortableUpdate.isWritable(tmp.resolve("missing")));
	}

	@Test
	void staleTempDirsKeepsRecentFoldersAndOtherNames() {
		final Instant now = Instant.parse("2026-10-26T12:00:00Z");
		final Map<String, Instant> dirs = Map.of(
				"synoRight-update-1", now.minus(Duration.ofDays(3)), // abandonné
				"synoRight-update-2", now.minus(Duration.ofHours(25)), // plus d'un jour
				"synoRight-update-3", now.minus(Duration.ofHours(2)), // du jour : un script peut s'en servir
				"synoRight-update-4", now.minus(Duration.ofDays(1)), // exactement un jour : gardé
				"other-update-1", now.minus(Duration.ofDays(10)), // autre préfixe
				"synoRight-repair.lock", now.minus(Duration.ofDays(10)));

		assertEquals(List.of("synoRight-update-1", "synoRight-update-2"), PortableUpdate.staleTempDirs(dirs, now));
	}

	@Test
	void otherInstancesOnTheRealProcessesIgnoresTheCurrentProcess() {
		final Path javaHome = Path.of(System.getProperty("java.home"));
		assertTrue(PortableUpdate.otherInstances(javaHome).stream()
				.noneMatch(p -> p.pid() == ProcessHandle.current().pid()));
	}

	@Test
	void scriptCommand() {
		final Path script = Path.of("C:/Temp/synoRight-update-1/apply-update.ps1");
		final Path appDir = Path.of("C:/Program Files/synoRight");
		final Path newDir = Path.of("C:/Temp/synoRight-update-1/synoRight");
		final Path relaunch = appDir.resolve("synoRightGui.exe");
		final Path logDir = Path.of("C:/Users/me/AppData/Roaming/synoRight");
		final Path configDir = Path.of("D:/my config");

		assertEquals(List.of(PortableUpdate.powershell().toString(), "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File",
				script.toString(), "-AppPid", "1234", "-AppDir", appDir.toString(), "-NewDir", newDir.toString(), "-Relaunch",
				relaunch.toString(), "-LogDir", logDir.toString(), "-ConfigDir", configDir.toString()),
				PortableUpdate.scriptCommand(script, 1234, appDir, newDir, relaunch, logDir, configDir));
	}

	@Test
	void powershellIsTheSystemOneWhenSystemRootIsKnown() {
		final String systemRoot = System.getenv("SystemRoot");
		if (systemRoot == null) {
			assertEquals(Path.of("powershell"), PortableUpdate.powershell());
		} else {
			assertEquals(Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe"), PortableUpdate.powershell());
		}
	}

	@Test
	void scriptResourceIsUtf8WithBomAndCrlf() throws IOException {
		final byte[] bytes;
		try (var in = PortableUpdate.class.getResourceAsStream("/update/apply-update.ps1")) {
			bytes = in.readAllBytes();
		}
		assertEquals((byte) 0xEF, bytes[0]);
		assertEquals((byte) 0xBB, bytes[1]);
		assertEquals((byte) 0xBF, bytes[2]);
		final String text = new String(bytes, StandardCharsets.UTF_8);
		assertFalse(text.replace("\r\n", "").contains("\n"), "LF without CR");
	}

	/** Processus simulé : seuls le pid et la commande servent. */
	private record FakeProcess(long pid, Optional<String> command, Optional<String> commandLine) implements ProcessHandle {

		FakeProcess(final long pid, final Optional<String> command) {
			this(pid, command, command);
		}

		@Override
		public Optional<ProcessHandle> parent() {
			return Optional.empty();
		}

		@Override
		public Stream<ProcessHandle> children() {
			return Stream.empty();
		}

		@Override
		public Stream<ProcessHandle> descendants() {
			return Stream.empty();
		}

		@Override
		public Info info() {
			return new Info() {
				@Override
				public Optional<String> command() {
					return command;
				}

				@Override
				public Optional<String> commandLine() {
					return commandLine;
				}

				@Override
				public Optional<String[]> arguments() {
					return Optional.empty();
				}

				@Override
				public Optional<Instant> startInstant() {
					return Optional.empty();
				}

				@Override
				public Optional<Duration> totalCpuDuration() {
					return Optional.empty();
				}

				@Override
				public Optional<String> user() {
					return Optional.empty();
				}
			};
		}

		@Override
		public CompletableFuture<ProcessHandle> onExit() {
			return new CompletableFuture<>();
		}

		@Override
		public boolean supportsNormalTermination() {
			return false;
		}

		@Override
		public boolean destroy() {
			return false;
		}

		@Override
		public boolean destroyForcibly() {
			return false;
		}

		@Override
		public boolean isAlive() {
			return true;
		}

		@Override
		public int compareTo(final ProcessHandle other) {
			return Long.compare(pid, other.pid());
		}
	}
}
