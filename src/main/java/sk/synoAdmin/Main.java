package sk.synoAdmin;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import picocli.CommandLine;
import picocli.CommandLine.ParameterException;
import sk.synoAdmin.CliParams.Actions;
import sk.synoAdmin.engine.Group;
import sk.synoAdmin.engine.RightEngine;
import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.synology.SynoApiClient;
import sk.synoAdmin.synology.SynoGroup;

public class Main {

	public static void main(final String[] args) {
		int exitCode = 0;
		try {
			LogUtil.runMonitored(() -> {
				doMain(args);
			}, "Total time");
		} catch (final ParameterException e) {
			System.err.println(e.getMessage());
			e.getCommandLine().usage(System.err);
			exitCode = 2;
		} catch (final UserException e) {
			System.err.println(e.getMessage());
			exitCode = 1;
		} catch (final Exception e) {
			e.printStackTrace();
			exitCode = 1;
		}

		ConsoleUtil.pauseBeforeExit();

		if (exitCode != 0) {
			System.exit(exitCode);
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

		if (cliParams.getAction() == Actions.UNINSTALL_MENU) {
			ContextMenuInstaller.uninstall();
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

		if (cliParams.getAction() == Actions.INSTALL_MENU) {
			ContextMenuInstaller.install(pref.getGroups(), pref.getNasFolder(),
					Paths.get(cliParams.getConfigFolder()).toAbsolutePath().normalize());
			return;
		}

		final RightEngine re = new RightEngine(pref);

		switch (cliParams.getAction()) {
			case TEST_CONNEXION:
				re.getFullState();
				break;
			case FULL_DB_TO_FS:
				re.getFullState();
				LogUtil.runMonitored(() -> re.dbToFs(cliParams.isYes()), "Action time");
				break;
			case FULL_FS_TO_DB:
				re.getFullState();
				LogUtil.runMonitored(() -> re.fsToDb(cliParams.isYes()), "Action time");
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
					.filter(g -> String.valueOf(g.gid()).equals(grp.getId()))
					.findAny();

			if (nasGroup.isEmpty()) {
				problems.add("Le groupe '" + grp.getName() + "' (id " + grp.getId() + ") n'existe pas sur le NAS.");
			} else if (!nasGroup.get().name().equalsIgnoreCase(grp.getName())) {
				System.out.println("Attention : le groupe d'id " + grp.getId() + " s'appelle '" + nasGroup.get().name()
						+ "' sur le NAS, mais '" + grp.getName() + "' dans la configuration.");
			}
		}

		if (!problems.isEmpty()) {
			final String available = nasGroups.stream()
					.map(g -> g.gid() + ":" + g.name())
					.collect(Collectors.joining(", "));
			throw new UserException(String.join("\n", problems)
					+ "\nGroupes disponibles sur le NAS : " + available
					+ "\nCorriger le fichier de configuration ou relancer INIT.");
		}

		System.out.println("Groupes vérifiés sur le NAS : OK.");
	}

}
