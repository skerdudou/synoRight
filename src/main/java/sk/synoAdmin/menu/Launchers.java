package sk.synoAdmin.menu;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import sk.synoAdmin.UserException;

/**
 * Lanceurs de l'application : exe jpackage (synoRight.exe console, synoRightGui.exe sans console) ou jar.
 */
public final class Launchers {

	public static final String GUI_EXE = "synoRightGui.exe";

	private Launchers() {
		// utility
	}

	/** Commande de lancement de l'IHM, à compléter par les arguments de la commande 'gui'. */
	public static List<String> gui() {
		return gui(System.getProperty("jpackage.app-path"), Paths.get(System.getProperty("java.home")), currentJar());
	}

	static List<String> gui(final String appPath, final Path javaHome, final Path jarLocation) {
		if (appPath != null) {
			return List.of(Paths.get(appPath).resolveSibling(GUI_EXE).toString());
		}
		if (jarLocation == null || !jarLocation.getFileName().toString().endsWith(".jar")) {
			throw new UserException("Impossible de déterminer le jar : lancé depuis '" + jarLocation + "'. Lancer l'installation depuis le jar ou l'exe.");
		}
		return List.of(javaHome.resolve("bin").resolve("javaw.exe").toString(), "-jar", jarLocation.toString(), "gui");
	}

	/** Dossier de l'application jpackage (vide hors exe). */
	public static Optional<Path> appDir() {
		final String appPath = System.getProperty("jpackage.app-path");
		return appPath == null ? Optional.empty() : Optional.of(Paths.get(appPath).getParent());
	}

	private static Path currentJar() {
		try {
			return Paths.get(Launchers.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		} catch (final URISyntaxException e) {
			throw new RuntimeException(e);
		}
	}
}
