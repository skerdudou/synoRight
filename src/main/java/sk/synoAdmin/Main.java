package sk.synoAdmin;

import java.util.concurrent.TimeUnit;

import picocli.CommandLine;
import sk.synoAdmin.CliParams.Actions;
import sk.synoAdmin.engine.RightEngine;
import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.synology.SynoApiClient;

public class Main {

	public static void main(final String[] args) throws InterruptedException {
		try {
			final long start = System.nanoTime();
			final CliParams cliParams = CommandLine.populateCommand(new CliParams(), args);

			if (cliParams.isUsageHelpRequested()) {
				CommandLine.usage(cliParams, System.out);
				return;
			}

			final long endInit = System.nanoTime();
			System.out.println("Init time : " + (endInit - start) / 1000000);

			if (Actions.INIT.equals(cliParams.getAction())) {
				createPrefs();
			} else {
				if (Actions.ADD_GRP.equals(cliParams.getAction()) || Actions.DEL_GRP.equals(cliParams.getAction())) {
					if (cliParams.getGid() == null) {
						throw new UserException("Le paramètre de group est obligatoire");
					}
					if (cliParams.getTargetFolder() == null) {
						throw new UserException("Le paramètre de répertoire est obligatoire");
					}
				}

				final Preferences pref = readPrefs(cliParams);

				final RightEngine re = new RightEngine(pref);
				if (Actions.FULL_DB_TO_FS.equals(cliParams.getAction()) ||
						Actions.FULL_FS_TO_DB.equals(cliParams.getAction())) {
					re.getFullState();
				}

				final long startAction = System.nanoTime();

				switch (cliParams.getAction()) {
					case FULL_DB_TO_FS:
						re.dbToFs();
						break;
					case FULL_FS_TO_DB:
						re.fsToDb();
						break;
					case ADD_GRP:
						re.addRightToFolder(cliParams.getTargetFolder(), pref.getGroupByName("kerdudou"));
						break;
					case DEL_GRP:
						re.removeRightToFolder(cliParams.getTargetFolder(), pref.getGroupByName("kerdudou"));
						break;
					default:
						throw new RuntimeException("Action non prise en charge");
				}

				final long endAction = System.nanoTime();
				System.out.println("Action time : " + (endAction - startAction) / 1000000);
			}

			final long end = System.nanoTime();
			System.out.println("End (total : " + (end - start) / 1000000 + ")");
		} catch (final Exception e) {
			e.printStackTrace();
			TimeUnit.SECONDS.sleep(25);
		} finally {
			TimeUnit.SECONDS.sleep(5);
		}
	}

	private static void createPrefs() {
		final Preferences pref = Preferences.fromPrompt("./");

		new SynoApiClient(pref); // test d'accès à l'API
		// TODO : check groups

		pref.toFile();
	}

	private static Preferences readPrefs(final CliParams cliParams) {
		final long start = System.nanoTime();

		final Preferences prefs = Preferences.fromFile(cliParams.getConfigFolder());

		final long end = System.nanoTime();
		System.out.println("Prefs time : " + (end - start) / 1000000);

		return prefs;
	}

}