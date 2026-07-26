package sk.synoAdmin;

import java.util.concurrent.TimeUnit;

import picocli.CommandLine;
import picocli.CommandLine.ParameterException;
import sk.synoAdmin.CliParams.Actions;
import sk.synoAdmin.engine.RightEngine;
import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.synology.SynoApiClient;

public class Main {

	public static void main(final String[] args) throws InterruptedException {
		try {
			LogUtil.runMonitored(() -> {
				doMain(args);
			}, "Total time");
		} catch (final ParameterException e) {
			System.err.println(e.getMessage());
			e.getCommandLine().usage(System.err);
			TimeUnit.SECONDS.sleep(25);
		} catch (final UserException e) {
			System.err.println(e.getMessage());
			TimeUnit.SECONDS.sleep(25);
		} catch (final Exception e) {
			e.printStackTrace();
			TimeUnit.SECONDS.sleep(25);
		} finally {
			// laisse le temps de lire la console quand on est lancé depuis le menu contextuel
			TimeUnit.SECONDS.sleep(5);
		}
	}

	private static void doMain(final String[] args) {
		final CliParams cliParams = LogUtil.getMonitored(
				() -> CommandLine.populateCommand(new CliParams(), args),
				"Init time");

		if (cliParams.isUsageHelpRequested()) {
			CommandLine.usage(cliParams, System.out);
			return;
		}

		if (cliParams.getAction() == Actions.INIT) {
			createPrefs();
			return;
		}

		if (cliParams.getAction() == Actions.ADD_GRP || cliParams.getAction() == Actions.DEL_GRP) {
			if (cliParams.getGroupName() == null) {
				throw new UserException("Le paramètre de groupe est obligatoire");
			}
			if (cliParams.getTargetFolder() == null) {
				throw new UserException("Le paramètre de répertoire est obligatoire");
			}
		}

		final Preferences pref = LogUtil.getMonitored(
				() -> Preferences.fromFile(cliParams.getConfigFolder()),
				"Prefs time");

		final RightEngine re = new RightEngine(pref);

		switch (cliParams.getAction()) {
			case TEST_CONNEXION:
				re.getFullState();
				break;
			case FULL_DB_TO_FS:
				re.getFullState();
				LogUtil.runMonitored(re::dbToFs, "Action time");
				break;
			case FULL_FS_TO_DB:
				re.getFullState();
				LogUtil.runMonitored(re::fsToDb, "Action time");
				break;
			case ADD_GRP:
				re.login();
				LogUtil.runMonitored(
						() -> re.addRightToFolder(cliParams.getTargetFolder(), pref.getGroupByName(cliParams.getGroupName())),
						"Action time");
				break;
			case DEL_GRP:
				re.login();
				LogUtil.runMonitored(
						() -> re.removeRightToFolder(cliParams.getTargetFolder(), pref.getGroupByName(cliParams.getGroupName())),
						"Action time");
				break;
			default:
				throw new UserException("Action non prise en charge : " + cliParams.getAction());
		}
	}

	private static void createPrefs() {
		final Preferences pref = Preferences.fromPrompt("./");

		new SynoApiClient(pref); // test d'accès à l'API
		// TODO : check groups

		pref.toFile();
	}

}
