package sk.synoAdmin;

import java.io.File;

import sk.synoAdmin.engine.RightEngine;
import sk.synoAdmin.prefs.Preferences;

public class Main {

	private static String rootFolder;

	public static void main(final String[] args) {
		init(args);

		final Preferences pref = Preferences.fromFile(rootFolder);
		final RightEngine re = new RightEngine(pref);

		re.init();
//
//		re.dbToFs();

		System.out.println("End");
	}

	private static void init(final String[] args) {
		if (args.length != 1) {
			throw new UserException("Attendu 1 paramètre : Le chemin vers le répertoire photo du NAS");
		}

		final File rootDir = new File(args[0]);
		if (!rootDir.exists() || !rootDir.isDirectory()) {
			throw new UserException("'" + args[0] + "' n'est pas un nom de répertoire valide.");
		}

		rootFolder = args[0];
	}

	private static void createPrefs() {
		final Preferences pref = Preferences.fromPrompt(rootFolder);

		// TODO : check successfull
		pref.toFile();
	}

}