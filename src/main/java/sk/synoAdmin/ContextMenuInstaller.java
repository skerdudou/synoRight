package sk.synoAdmin;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import sk.synoAdmin.engine.Group;

/**
 * Installe le menu contextuel « Droits Synology Photos » dans l'Explorateur Windows.
 *
 * Les clés sont écrites sous HKEY_CURRENT_USER\Software\Classes : l'installation ne
 * concerne que l'utilisateur courant et ne demande pas de droits administrateur.
 * Les commandes générées pointent vers le lanceur en cours d'exécution (synoRight.exe
 * ou java + jar) ; relancer 'menu install' après un déplacement de l'application.
 * Les ajouts/suppressions de groupes ('groups add/remove') réinstallent le menu.
 */
public final class ContextMenuInstaller {

	private static final String CLASSES_ROOT = "HKEY_CURRENT_USER\\Software\\Classes\\";
	private static final String FOLDER_KEY = CLASSES_ROOT + "Directory\\shell\\01SynoRight";
	private static final String BACKGROUND_KEY = CLASSES_ROOT + "Directory\\Background\\shell\\01SynoRight";
	private static final String SUBMENU_KEY = CLASSES_ROOT + "Directory\\ContextMenus\\SynoRight";
	// référence relative à HKCR (fusion HKLM/HKCU) utilisée par ExtendedSubCommandsKey
	private static final String SUBMENU_REF = "Directory\\ContextMenus\\SynoRight";
	// flags repris de l'ancien install.reg : séparateur après l'entrée
	private static final String SEPARATOR_FLAGS = "\"CommandFlags\"=dword:00004140";

	private ContextMenuInstaller() {
		// utility
	}

	public static void install(final List<Group> groups, final String nasFolder, final Path configFolder) {
		checkWindows();

		final String content = buildRegFileContent(groups, nasFolder, currentLauncher(), configFolder);

		// purge une éventuelle installation précédente (entrées de groupes obsolètes)
		runReg(buildUninstallCommands(), true);

		Path regFile = null;
		try {
			regFile = Files.createTempFile("synoRight", ".reg");
			// format .reg version 5 : UTF-16LE avec BOM
			Files.writeString(regFile, (char) 0xFEFF + content, StandardCharsets.UTF_16LE);
			runReg(List.of(List.of("reg", "import", regFile.toString())), false);
		} catch (final IOException e) {
			throw new RuntimeException("Impossible d'écrire le fichier .reg temporaire", e);
		} finally {
			if (regFile != null) {
				try {
					Files.deleteIfExists(regFile);
				} catch (final IOException e) {
					// fichier temporaire : sans conséquence
				}
			}
		}

		System.out.println("Menu contextuel installé pour l'utilisateur courant (" + groups.size() + " groupe(s) configuré(s)).");
	}

	public static void uninstall() {
		checkWindows();
		runReg(buildUninstallCommands(), true);
		System.out.println("Menu contextuel désinstallé.");
	}

	/**
	 * Indique si le menu est installé pour l'utilisateur courant (toujours faux hors Windows).
	 */
	public static boolean isInstalled() {
		if (!isWindows()) {
			return false;
		}
		try {
			final Process process = new ProcessBuilder("reg", "query", FOLDER_KEY).redirectErrorStream(true).start();
			process.getInputStream().readAllBytes();
			return process.waitFor() == 0;
		} catch (final IOException e) {
			return false;
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * @param launcher commande de lancement de l'application (exe, ou java -jar jar)
	 */
	static String buildRegFileContent(final List<Group> groups, final String nasFolder, final List<String> launcher, final Path configFolder) {
		final StringBuilder reg = new StringBuilder("Windows Registry Editor Version 5.00\r\n");

		// entrée racine du menu : clic droit sur un dossier (limité à l'arborescence photo)...
		appendKey(reg, FOLDER_KEY,
				"\"MUIVerb\"=\"" + escape("Droits Synology Photos") + "\"",
				"\"ExtendedSubCommandsKey\"=\"" + escape(SUBMENU_REF) + "\"",
				"\"AppliesTo\"=\"" + escape(nasFolder + "\\*") + "\"");

		// ... et clic droit sur le fond du dossier ouvert
		appendKey(reg, BACKGROUND_KEY,
				"\"MUIVerb\"=\"" + escape("Droits Synology Photos") + "\"",
				"\"ExtendedSubCommandsKey\"=\"" + escape(SUBMENU_REF) + "\"");

		final String baseCmd = launcher.stream()
				.map(arg -> arg.startsWith("-") ? arg : quote(arg))
				.collect(Collectors.joining(" "))
				+ " -c " + quote(configFolder.toString()) + " --pause";
		final String target = " " + quote("%V");

		int index = 1;
		appendMenuEntry(reg, index++, "show", "Voir les droits de ce dossier", false, baseCmd + " show" + target);
		appendMenuEntry(reg, index++, "status", "État de la synchro", true, baseCmd + " status");
		appendMenuEntry(reg, index++, "dbToFs", "Full synchro Synology Photos -> Répertoire", false, baseCmd + " pull");
		appendMenuEntry(reg, index++, "fsToDb", "Full synchro Répertoire -> Synology Photos", true, baseCmd + " push");

		for (final Group group : groups) {
			final String keyName = group.getName().replaceAll("[^A-Za-z0-9]", "");
			final String grpParams = " " + quote(group.getName()) + target;
			appendMenuEntry(reg, index++, "add" + keyName, "Ajouter " + group.getName(), false,
					baseCmd + " grant" + grpParams);
			appendMenuEntry(reg, index++, "del" + keyName, "Supprimer " + group.getName(), true,
					baseCmd + " revoke" + grpParams);
		}

		return reg.toString();
	}

	private static void appendMenuEntry(final StringBuilder reg, final int index, final String name, final String label, final boolean separatorAfter, final String command) {
		final String key = SUBMENU_KEY + "\\shell\\" + String.format("%02d%s", index, name);

		if (separatorAfter) {
			appendKey(reg, key, "\"MUIVerb\"=\"" + escape(label) + "\"", SEPARATOR_FLAGS);
		} else {
			appendKey(reg, key, "\"MUIVerb\"=\"" + escape(label) + "\"");
		}
		appendKey(reg, key + "\\command", "@=\"" + command + "\"");
	}

	private static void appendKey(final StringBuilder reg, final String key, final String... values) {
		reg.append("\r\n[").append(key).append("]\r\n");
		for (final String value : values) {
			reg.append(value).append("\r\n");
		}
	}

	// argument entre guillemets, échappé pour une valeur .reg
	private static String quote(final String value) {
		return "\\\"" + escape(value) + "\\\"";
	}

	// échappement du format .reg : backslashs et guillemets
	private static String escape(final String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	static List<List<String>> buildUninstallCommands() {
		return List.of(
				List.of("reg", "delete", FOLDER_KEY, "/f"),
				List.of("reg", "delete", BACKGROUND_KEY, "/f"),
				List.of("reg", "delete", SUBMENU_KEY, "/f"));
	}

	private static void runReg(final List<List<String>> commands, final boolean ignoreErrors) {
		for (final List<String> command : commands) {
			try {
				final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
				final String output = new String(process.getInputStream().readAllBytes());
				if (process.waitFor() != 0 && !ignoreErrors) {
					throw new UserException("Échec de '" + String.join(" ", command) + "' : " + output.trim());
				}
			} catch (final IOException e) {
				throw new RuntimeException("Impossible d'exécuter reg.exe", e);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException(e);
			}
		}
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
	}

	private static void checkWindows() {
		if (!isWindows()) {
			throw new UserException("Le menu contextuel n'est disponible que sous Windows (OS détecté : " + System.getProperty("os.name", "") + ").");
		}
	}

	/**
	 * Commande de lancement courante : l'exe généré par jpackage s'il est utilisé,
	 * sinon le java courant avec le jar.
	 */
	private static List<String> currentLauncher() {
		final String appPath = System.getProperty("jpackage.app-path");
		if (appPath != null) {
			return List.of(appPath);
		}

		final Path javaExe = Paths.get(System.getProperty("java.home"), "bin", "java.exe");
		try {
			final Path location = Paths.get(ContextMenuInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			if (!location.getFileName().toString().endsWith(".jar")) {
				throw new UserException("Impossible de déterminer le jar : lancé depuis '" + location + "'. Lancer l'installation depuis le jar ou l'exe.");
			}
			return List.of(javaExe.toString(), "-jar", location.toString());
		} catch (final URISyntaxException e) {
			throw new RuntimeException(e);
		}
	}
}
