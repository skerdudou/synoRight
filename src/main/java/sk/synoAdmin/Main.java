package sk.synoAdmin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import picocli.CommandLine;
import picocli.CommandLine.ParameterException;
import sk.synoAdmin.CliParams.Actions;
import sk.synoAdmin.engine.Group;
import sk.synoAdmin.engine.RightEngine;
import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.synology.SynoApiClient;
import sk.synoAdmin.synology.pojo.SynoGroupListResponse.SynoGroup;

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
		pref.toFile();

		// test d'accès à l'API (le login persiste la session dans le fichier tout juste créé)
		final SynoApiClient synoClient = new SynoApiClient(pref);
		synoClient.login();

		checkGroups(synoClient, pref);
	}

	/**
	 * Vérifie que les groupes configurés existent bien sur le NAS (id et nom).
	 */
	private static void checkGroups(final SynoApiClient synoClient, final Preferences pref) {
		final List<SynoGroup> nasGroups;
		try {
			nasGroups = synoClient.getGroups();
		} catch (final RuntimeException e) {
			// vérification best-effort : la conf est déjà écrite, on ne bloque pas l'INIT
			System.out.println("Impossible de vérifier les groupes configurés : " + e.getMessage());
			return;
		}

		final List<String> problems = new ArrayList<>();
		for (final Group grp : pref.getGroups()) {
			final Optional<SynoGroup> nasGroup = nasGroups.stream()
					.filter(g -> String.valueOf(g.getGid()).equals(grp.getId()))
					.findAny();

			if (nasGroup.isEmpty()) {
				problems.add("Le groupe '" + grp.getName() + "' (id " + grp.getId() + ") n'existe pas sur le NAS.");
			} else if (!nasGroup.get().getName().equalsIgnoreCase(grp.getName())) {
				System.out.println("Attention : le groupe d'id " + grp.getId() + " s'appelle '" + nasGroup.get().getName()
						+ "' sur le NAS, mais '" + grp.getName() + "' dans la configuration.");
			}
		}

		if (!problems.isEmpty()) {
			final String available = nasGroups.stream()
					.map(g -> g.getGid() + ":" + g.getName())
					.collect(Collectors.joining(", "));
			throw new UserException(String.join("\n", problems)
					+ "\nGroupes disponibles sur le NAS : " + available
					+ "\nCorriger le fichier de configuration ou relancer INIT.");
		}

		System.out.println("Groupes vérifiés sur le NAS : OK.");
	}

}
