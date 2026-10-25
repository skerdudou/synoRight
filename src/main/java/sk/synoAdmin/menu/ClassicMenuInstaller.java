package sk.synoAdmin.menu;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import sk.synoAdmin.UserException;
import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.menu.MenuDefinition.MenuItem;

/**
 * Installe le menu contextuel classique « Droits Synology Photos » dans l'Explorateur Windows.
 *
 * Les clés sont écrites sous HKEY_CURRENT_USER\Software\Classes : l'installation ne
 * concerne que l'utilisateur courant et ne demande pas de droits administrateur.
 * Les commandes générées pointent vers le lanceur de l'IHM (synoRightGui.exe ou javaw + jar) ;
 * réinstaller le menu après un déplacement de l'application.
 */
public final class ClassicMenuInstaller {

	private static final String CLASSES_ROOT = "HKEY_CURRENT_USER\\Software\\Classes\\";
	private static final String FOLDER_KEY = CLASSES_ROOT + "Directory\\shell\\01SynoRight";
	private static final String BACKGROUND_KEY = CLASSES_ROOT + "Directory\\Background\\shell\\01SynoRight";
	static final String SUBMENU_KEY = CLASSES_ROOT + "Directory\\ContextMenus\\SynoRight";
	// référence relative à HKCR (fusion HKLM/HKCU) utilisée par ExtendedSubCommandsKey
	private static final String SUBMENU_REF = "Directory\\ContextMenus\\SynoRight";
	// ECF_SEPARATORBEFORE
	private static final String SEPARATOR_BEFORE_FLAGS = "\"CommandFlags\"=dword:00000020";

	private ClassicMenuInstaller() {
		// utility
	}

	public static void install(final List<MenuItem> items, final String nasFolder, final Path configDir) {
		checkWindows();
		final String content = buildRegFileContent(items, nasFolder, Launchers.gui(), configDir);
		// purge d'une installation précédente (entrées obsolètes)
		buildUninstallCommands().forEach(ProcessRunner::run);
		importRegFile(content);
	}

	public static void uninstall() {
		checkWindows();
		buildUninstallCommands().forEach(ProcessRunner::run);
	}

	/** Indique si le menu est installé pour l'utilisateur courant (toujours faux hors Windows). */
	public static boolean isInstalled() {
		return ProcessRunner.isWindows() && ProcessRunner.run(List.of("reg", "query", FOLDER_KEY)).ok();
	}

	/**
	 * @param launcher commande de lancement de l'IHM (exe, ou javaw -jar jar gui)
	 */
	static String buildRegFileContent(final List<MenuItem> items, final String nasFolder, final List<String> launcher, final Path configDir) {
		final StringBuilder reg = new StringBuilder("Windows Registry Editor Version 5.00\r\n");

		// entrée racine du menu : clic droit sur un dossier (limité à l'arborescence photo)...
		// icône de l'entrée racine : celle du lanceur, comme le menu Windows 11
		final String icon = "\"Icon\"=\"" + escape(launcher.get(0) + ",0") + "\"";
		appendKey(reg, FOLDER_KEY,
				"\"MUIVerb\"=\"" + escape(MenuDefinition.rootTitle()) + "\"",
				icon,
				"\"ExtendedSubCommandsKey\"=\"" + escape(SUBMENU_REF) + "\"",
				"\"AppliesTo\"=\"" + escape(nasFolder + "\\*") + "\"");
		// ... et clic droit sur le fond du dossier ouvert
		appendKey(reg, BACKGROUND_KEY,
				"\"MUIVerb\"=\"" + escape(MenuDefinition.rootTitle()) + "\"",
				icon,
				"\"ExtendedSubCommandsKey\"=\"" + escape(SUBMENU_REF) + "\"");

		final List<String> base = new ArrayList<>(launcher);
		base.add("-c");
		base.add(configDir.toString());
		for (final MenuItem item : items) {
			final String key = SUBMENU_KEY + "\\shell\\" + item.key();
			final List<String> args = new ArrayList<>(base);
			args.addAll(item.args());
			final int path = args.indexOf(MenuDefinition.PATH);
			if (path >= 0) {
				// sélection multiple : un processus par dossier, regroupés par le premier (SelectionCollector) ;
				// avant "%V", qui reste le dernier argument : "P:\" absorberait l'argument suivant
				args.add(path, "--collect");
			}
			if (item.separatorBefore()) {
				appendKey(reg, key, "\"MUIVerb\"=\"" + escape(item.title()) + "\"", SEPARATOR_BEFORE_FLAGS);
			} else {
				appendKey(reg, key, "\"MUIVerb\"=\"" + escape(item.title()) + "\"");
			}
			appendKey(reg, key + "\\command", "@=\"" + escape(CommandLines.join(args)) + "\"");
		}
		return reg.toString();
	}

	/** Importe un contenu .reg (format version 5, UTF-16LE avec BOM). */
	static void importRegFile(final String content) {
		Path regFile = null;
		try {
			regFile = Files.createTempFile("synoRight", ".reg");
			Files.writeString(regFile, (char) 0xFEFF + content, StandardCharsets.UTF_16LE);
			final ProcessRunner.Result result = ProcessRunner.run(List.of("reg", "import", regFile.toString()));
			if (!result.ok()) {
				throw new UserException(Messages.get("menu.install.regImportFailed", result.output()));
			}
		} catch (final IOException e) {
			throw new RuntimeException(Messages.get("menu.install.regFileError"), e);
		} finally {
			if (regFile != null) {
				try {
					Files.deleteIfExists(regFile);
				} catch (final IOException e) {
					// fichier temporaire : sans conséquence
				}
			}
		}
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

	private static void checkWindows() {
		if (!ProcessRunner.isWindows()) {
			throw new UserException(Messages.get("menu.install.windowsOnly", System.getProperty("os.name", "")));
		}
	}
}
