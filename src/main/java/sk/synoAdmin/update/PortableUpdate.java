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
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import sk.synoAdmin.LogUtil;
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
	/** Préfixe des dossiers temporaires de mise à jour (dans java.io.tmpdir). */
	public static final String TEMP_PREFIX = "synoRight-update-";
	/** Âge à partir duquel un dossier temporaire de mise à jour est abandonné (aucun script ne s'en sert plus). */
	private static final Duration TEMP_MAX_AGE = Duration.ofDays(1);
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

	/**
	 * Autres processus lancés depuis appDir (fenêtres des droits, synchronisation…), hors processus courant. Limite : une
	 * instance élevée cache son exécutable ; sous Windows le JDK ne fournit alors ni commande ni ligne de commande, elle
	 * n'est donc pas vue ici. Le script de remplacement la repère par son nom pendant son attente et abandonne (relance de
	 * l'ancienne version, rien n'est remplacé).
	 */
	public static List<ProcessHandle> otherInstances(final Path appDir) {
		try (Stream<ProcessHandle> processes = ProcessHandle.allProcesses()) {
			return otherInstances(appDir, ProcessHandle.current().pid(), processes);
		}
	}

	/**
	 * Processus de la liste lancés depuis appDir (comparaison insensible à la casse), hors selfPid ; sans commande connue,
	 * un processus dont la ligne de commande (si fournie) contient « synoRight » compte aussi (dossier invérifiable).
	 */
	public static List<ProcessHandle> otherInstances(final Path appDir, final long selfPid, final Stream<ProcessHandle> processes) {
		final String prefix = key(appDir.toAbsolutePath().normalize().toString()) + key(File.separator);
		return processes.filter(p -> p.pid() != selfPid)
				.filter(p -> command(p).map(c -> key(c).startsWith(prefix))
						.orElseGet(() -> commandLine(p).map(c -> key(c).contains(key(ROOT))).orElse(false)))
				.toList();
	}

	private static Optional<String> commandLine(final ProcessHandle process) {
		return process.info().commandLine().filter(c -> !c.isBlank());
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

	/**
	 * Ligne de commande du script de remplacement (arguments séparés : chemins avec espaces sans souci) ; configDir est
	 * repassé par -c à l'application relancée.
	 */
	public static List<String> scriptCommand(final Path script, final long pid, final Path appDir, final Path newDir,
			final Path relaunch, final Path logDir, final Path configDir) {
		return List.of(powershell().toString(), "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File",
				script.toString(), "-AppPid", Long.toString(pid), "-AppDir", appDir.toString(), "-NewDir", newDir.toString(),
				"-Relaunch", relaunch.toString(), "-LogDir", logDir.toString(), "-ConfigDir", configDir.toString());
	}

	/**
	 * Vrai si l'utilisateur peut écrire dans dir (fichier témoin créé puis supprimé) ; faux : le script demandera l'accord
	 * d'un administrateur pour la copie.
	 */
	public static boolean isWritable(final Path dir) {
		try {
			final Path probe = Files.createTempFile(dir, ".update-probe-", ".tmp");
			Files.deleteIfExists(probe);
			return true;
		} catch (final IOException | SecurityException | UnsupportedOperationException e) {
			return false;
		}
	}

	/**
	 * Noms des dossiers temporaires de mise à jour abandonnés : préfixe {@link #TEMP_PREFIX}, modifiés il y a plus d'un
	 * jour (les plus récents peuvent encore servir à un script en cours).
	 *
	 * @param dirs dossiers de java.io.tmpdir (nom, date de modification)
	 */
	static List<String> staleTempDirs(final Map<String, Instant> dirs, final Instant now) {
		final Instant limit = now.minus(TEMP_MAX_AGE);
		return dirs.entrySet().stream()
				.filter(e -> e.getKey().startsWith(TEMP_PREFIX) && e.getValue().isBefore(limit))
				.map(Map.Entry::getKey)
				.sorted()
				.toList();
	}

	/** Supprime, au mieux, les dossiers temporaires de mise à jour abandonnés de java.io.tmpdir (erreurs journalisées en debug). */
	public static void deleteStaleTempDirs() {
		final Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
		final Map<String, Instant> dirs = new HashMap<>();
		try (Stream<Path> files = Files.list(tmp)) {
			files.filter(f -> f.getFileName().toString().startsWith(TEMP_PREFIX) && Files.isDirectory(f)).forEach(f -> {
				try {
					dirs.put(f.getFileName().toString(), Files.getLastModifiedTime(f).toInstant());
				} catch (final IOException e) {
					LogUtil.debug("Date de " + f + " illisible : " + e);
				}
			});
		} catch (final IOException | UncheckedIOException e) {
			LogUtil.debug("Dossier temporaire " + tmp + " illisible : " + e);
			return;
		}
		for (final String name : staleTempDirs(dirs, Instant.now())) {
			deleteTree(tmp.resolve(name));
		}
	}

	// suppression récursive au mieux : un fichier verrouillé reste, le reste est supprimé
	private static void deleteTree(final Path dir) {
		try (Stream<Path> paths = Files.walk(dir)) {
			paths.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (final IOException e) {
					LogUtil.debug("Suppression de " + p + " : " + e);
				}
			});
		} catch (final IOException | UncheckedIOException e) {
			LogUtil.debug("Suppression de " + dir + " : " + e);
		}
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
	 * le lance détaché (journal dans logDir, relance avec -c configDir), sans attendre : l'appelant doit quitter ensuite
	 * pour que le script puisse remplacer les fichiers.
	 * Appelée seulement depuis le processus de l'interface (synoRightGui.exe, sans console) : le script, lancé caché,
	 * n'hérite d'aucune console à fermer.
	 *
	 * @throws IllegalArgumentException si newDir n'est pas directement dans tempDir (le script ne supprime que son propre
	 *         dossier)
	 */
	public static void start(final Path tempDir, final Path newDir, final Path appDir, final Path logDir, final Path configDir) {
		final Path parent = newDir.toAbsolutePath().normalize().getParent();
		if (parent == null || !parent.equals(tempDir.toAbsolutePath().normalize())) {
			throw new IllegalArgumentException(newDir + " is not directly in " + tempDir);
		}
		try {
			Files.createDirectories(logDir);
			final Path script = copyScript(tempDir);
			final List<String> command = scriptCommand(script, ProcessHandle.current().pid(), appDir, newDir,
					appDir.resolve(GUI_EXE), logDir, configDir.toAbsolutePath().normalize());
			new ProcessBuilder(command).redirectOutput(Redirect.DISCARD).redirectError(Redirect.DISCARD).start();
		} catch (final IOException | UncheckedIOException e) {
			throw new UserException(Messages.get("update.portable.launchFailed", e.toString()), e);
		}
	}
}
