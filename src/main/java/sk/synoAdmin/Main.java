package sk.synoAdmin;

import java.io.File;

import sk.synoAdmin.engine.RightEngine;
import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.synology.SynoApiClient;

public class Main {

	private static String rootFolder;

	public static void main(final String[] args) {
		final long start = System.nanoTime();

		init(args);
//		createPrefs();

		final Preferences pref = Preferences.fromFile(rootFolder);

		final long endPref = System.nanoTime();
		System.out.println("Prefs time : " + (endPref - start) / 1000000);

		final RightEngine re = new RightEngine(pref);

		re.init();

		final long startMaj = System.nanoTime();

//		re.dbToFs();
		re.fsToDb();

		final long endMaj = System.nanoTime();
		System.out.println("Maj time : " + (endMaj - startMaj) / 1000000);

		final long end = System.nanoTime();
		System.out.println("End (total : " + (end - start) / 1000000 + ")");
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

		new SynoApiClient(pref); // test d'accès à l'API
		// TODO : check groups

		pref.toFile();
	}

}