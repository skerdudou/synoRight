package sk.synoAdmin.update;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import sk.synoAdmin.UserException;
import sk.synoAdmin.i18n.Messages;

/**
 * Mise à jour d'une copie portable (zip) : décompression contrôlée du zip de la release, détection des autres instances
 * lancées depuis le même dossier, puis lancement détaché du script PowerShell qui attend la fin de l'application,
 * remplace l'image de l'application (app, runtime, fichiers racine) et la relance.
 */
public final class PortableUpdate {

	/** Dossier racine du zip publié par la CI. */
	static final String ROOT = "synoRight";
	static final String GUI_EXE = "synoRightGui.exe";
	private static final String SCRIPT = "apply-update.ps1";
	private static final String SCRIPT_RESOURCE = "/update/" + SCRIPT;

	private PortableUpdate() {
		// utility
	}

	/**
	 * Décompresse zip dans targetDir et renvoie targetDir/synoRight. Refuse (sans rien écrire hors de targetDir) toute
	 * entrée dont le chemin sort de targetDir, ainsi qu'un zip sans dossier synoRight contenant synoRightGui.exe et des
	 * dossiers app et runtime non vides.
	 */
	public static Path extract(final Path zip, final Path targetDir) {
		final Path target = targetDir.toAbsolutePath().normalize();
		try (ZipFile file = new ZipFile(zip.toFile())) {
			// contrôle de toutes les entrées avant d'écrire quoi que ce soit
			final Enumeration<? extends ZipEntry> check = file.entries();
			while (check.hasMoreElements()) {
				destination(target, check.nextElement().getName());
			}
			final Enumeration<? extends ZipEntry> entries = file.entries();
			while (entries.hasMoreElements()) {
				final ZipEntry entry = entries.nextElement();
				final Path destination = destination(target, entry.getName());
				if (entry.isDirectory()) {
					Files.createDirectories(destination);
				} else {
					Files.createDirectories(destination.getParent());
					try (InputStream in = file.getInputStream(entry)) {
						Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
		} catch (final ZipException e) {
			throw badZip(e);
		} catch (final IOException e) {
			throw new UserException(Messages.get("update.portable.badZip", e.toString()), e);
		}
		final Path root = target.resolve(ROOT);
		if (!Files.isRegularFile(root.resolve(GUI_EXE))) {
			throw new UserException(Messages.get("update.portable.badZip", ROOT + "/" + GUI_EXE));
		}
		// app et runtime sont remplacés en miroir : vides ou absents, l'application serait détruite
		for (final String sub : List.of("app", "runtime")) {
			if (isEmptyOrMissing(root.resolve(sub))) {
				throw new UserException(Messages.get("update.portable.badZip", ROOT + "/" + sub + "/"));
			}
		}
		return root;
	}

	/** Archive illisible : message de l'exception, ou son type quand elle n'en a pas. */
	static UserException badZip(final ZipException e) {
		return new UserException(Messages.get("update.portable.badZip", e.getMessage() != null ? e.getMessage() : e.toString()), e);
	}

	private static boolean isEmptyOrMissing(final Path dir) {
		if (!Files.isDirectory(dir)) {
			return true;
		}
		try (Stream<Path> children = Files.list(dir)) {
			return children.findAny().isEmpty();
		} catch (final IOException | UncheckedIOException e) {
			throw new UserException(Messages.get("update.portable.badZip", e.toString()), e);
		}
	}

	// chemin de l'entrée dans target, refusé s'il est absolu ou en sort (zip slip)
	private static Path destination(final Path target, final String name) {
		final Path destination;
		try {
			if (name.startsWith("/") || name.startsWith("\\") || name.contains(":")) {
				throw new InvalidPathException(name, "absolute path");
			}
			destination = target.resolve(name).normalize();
		} catch (final InvalidPathException e) {
			throw new UserException(Messages.get("update.portable.badZip", name), e);
		}
		if (!destination.startsWith(target) || destination.equals(target)) {
			throw new UserException(Messages.get("update.portable.badZip", name));
		}
		return destination;
	}

	/** Autres processus lancés depuis appDir (fenêtres des droits, synchronisation…), hors processus courant. */
	public static List<ProcessHandle> otherInstances(final Path appDir) {
		try (Stream<ProcessHandle> processes = ProcessHandle.allProcesses()) {
			return otherInstances(appDir, ProcessHandle.current().pid(), processes);
		}
	}

	/** Processus de la liste lancés depuis appDir (comparaison insensible à la casse), hors selfPid. */
	public static List<ProcessHandle> otherInstances(final Path appDir, final long selfPid, final Stream<ProcessHandle> processes) {
		final String prefix = key(appDir.toAbsolutePath().normalize().toString()) + key(File.separator);
		return processes.filter(p -> p.pid() != selfPid)
				.filter(p -> command(p).map(c -> key(c).startsWith(prefix)).orElse(false))
				.toList();
	}

	// commande normalisée du processus, vide si inconnue (processus d'un autre utilisateur, déjà terminé…)
	private static Optional<String> command(final ProcessHandle process) {
		try {
			return process.info().command().map(c -> Path.of(c).toAbsolutePath().normalize().toString());
		} catch (final InvalidPathException e) {
			return Optional.empty();
		}
	}

	private static String key(final String path) {
		return path.toLowerCase(Locale.ROOT);
	}

	/** Ligne de commande du script de remplacement (arguments séparés : chemins avec espaces sans souci). */
	public static List<String> scriptCommand(final Path script, final long pid, final Path appDir, final Path newDir,
			final Path relaunch, final Path logDir) {
		return List.of(powershell().toString(), "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File",
				script.toString(), "-AppPid", Long.toString(pid), "-AppDir", appDir.toString(), "-NewDir", newDir.toString(),
				"-Relaunch", relaunch.toString(), "-LogDir", logDir.toString());
	}

	/** PowerShell du système (chemin complet : pas de powershell.exe trouvé ailleurs dans le PATH), « powershell » à défaut. */
	static Path powershell() {
		final String systemRoot = System.getenv("SystemRoot");
		return systemRoot == null || systemRoot.isBlank() ? Path.of("powershell")
				: Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
	}

	/** Copie le script (octet pour octet, BOM compris) dans dir et renvoie son chemin. */
	static Path copyScript(final Path dir) throws IOException {
		final Path script = dir.resolve(SCRIPT);
		try (InputStream in = PortableUpdate.class.getResourceAsStream(SCRIPT_RESOURCE)) {
			if (in == null) {
				throw new IOException("missing resource " + SCRIPT_RESOURCE);
			}
			Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
		}
		return script;
	}

	/**
	 * Copie le script dans tempDir (le dossier temporaire de la mise à jour, parent de newDir, supprimé par le script) et
	 * le lance détaché, sans attendre : l'appelant doit quitter ensuite pour que le script puisse remplacer les fichiers.
	 * Appelée seulement depuis le processus de l'interface (synoRightGui.exe, sans console) : le script, lancé caché,
	 * n'hérite d'aucune console à fermer.
	 *
	 * @throws IllegalArgumentException si newDir n'est pas directement dans tempDir (le script ne supprime que son propre
	 *         dossier)
	 */
	public static void start(final Path tempDir, final Path newDir, final Path appDir, final Path logDir) {
		final Path parent = newDir.toAbsolutePath().normalize().getParent();
		if (parent == null || !parent.equals(tempDir.toAbsolutePath().normalize())) {
			throw new IllegalArgumentException(newDir + " is not directly in " + tempDir);
		}
		try {
			Files.createDirectories(logDir);
			final Path script = copyScript(tempDir);
			final List<String> command = scriptCommand(script, ProcessHandle.current().pid(), appDir, newDir,
					appDir.resolve(GUI_EXE), logDir);
			new ProcessBuilder(command).redirectOutput(Redirect.DISCARD).redirectError(Redirect.DISCARD).start();
		} catch (final IOException | UncheckedIOException e) {
			throw new UserException(Messages.get("update.portable.launchFailed", e.toString()), e);
		}
	}
}
