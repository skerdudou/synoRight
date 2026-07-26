package sk.synoAdmin;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import sk.synoAdmin.engine.Group;

/**
 * Installe le menu contextuel « Droits Synology Photos » dans l'Explorateur Windows.
 *
 * Les clés sont écrites sous HKEY_CURRENT_USER\Software\Classes : l'installation ne
 * concerne que l'utilisateur courant et ne demande pas de droits administrateur.
 * Les commandes générées pointent vers le java et le jar en cours d'exécution ;
 * relancer INSTALL_MENU après un déplacement du jar ou un changement de groupes.
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

		final Path javaExe = Paths.get(System.getProperty("java.home"), "bin", "java.exe");
		final String content = buildRegFileContent(groups, nasFolder, javaExe, currentJar(), configFolder);

		// purge une éventuelle installation précédente (entrées de groupes obsolètes)
		runReg(buildUninstallCommands(), true);

		try {
			final Path regFile = Files.createTempFile("synoRight", ".reg");
			// format .reg version 5 : UTF-16LE avec BOM
			Files.writeString(regFile, (char) 0xFEFF + content, StandardCharsets.UTF_16LE);
			runReg(List.of(List.of("reg", "import", regFile.toString())), false);
			Files.delete(regFile);
		} catch (final IOException e) {
			throw new RuntimeException("Impossible d'écrire le fichier .reg temporaire", e);
		}

		System.out.println("Menu contextuel installé pour l'utilisateur courant (" + groups.size() + " groupe(s) configuré(s)).");
	}

	public static void uninstall() {
		checkWindows();
		runReg(buildUninstallCommands(), true);
		System.out.println("Menu contextuel désinstallé.");
	}

	static String buildRegFileContent(final List<Group> groups, final String nasFolder, final Path javaExe, final Path jarPath, final Path configFolder) {
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

		final String baseCmd = "\\\"" + escape(javaExe.toString()) + "\\\" -jar \\\"" + escape(jarPath.toString())
				+ "\\\" -c \\\"" + escape(configFolder.toString()) + "\\\"";

		int index = 1;
		appendMenuEntry(reg, index++, "dbToFs", "Full synchro Synology Photos -> Répertoire", false,
				baseCmd + " -a FULL_DB_TO_FS");
		appendMenuEntry(reg, index++, "fsToDb", "Full synchro Répertoire -> Synology Photos", true,
				baseCmd + " -a FULL_FS_TO_DB");

		for (final Group group : groups) {
			final String keyName = group.getName().replaceAll("[^A-Za-z0-9]", "");
			final String grpParams = " -g \\\"" + escape(group.getName()) + "\\\" -d \\\"%V\\\"";
			appendMenuEntry(reg, index++, "add" + keyName, "Ajouter " + group.getName(), false,
					baseCmd + " -a ADD_GRP" + grpParams);
			appendMenuEntry(reg, index++, "del" + keyName, "Supprimer " + group.getName(), true,
					baseCmd + " -a DEL_GRP" + grpParams);
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

	private static void checkWindows() {
		final String osName = System.getProperty("os.name", "");
		if (!osName.toLowerCase().startsWith("windows")) {
			throw new UserException("L'installation du menu contextuel n'est disponible que sous Windows (OS détecté : " + osName + ").");
		}
	}

	private static Path currentJar() {
		try {
			final Path location = Paths.get(ContextMenuInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			if (!location.getFileName().toString().endsWith(".jar")) {
				throw new UserException("Impossible de déterminer le jar : lancé depuis '" + location + "'. Lancer l'installation depuis le jar.");
			}
			return location;
		} catch (final URISyntaxException e) {
			throw new RuntimeException(e);
		}
	}
}
