package sk.synoAdmin.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import sk.synoAdmin.LogUtil;
import sk.synoAdmin.engine.Group;
import sk.synoAdmin.engine.RightEngine;
import sk.synoAdmin.engine.SyncPlan;
import sk.synoAdmin.prefs.Preferences;

/**
 * Commandes de lecture et de synchronisation des droits.
 */
final class RightCommands {

	private RightCommands() {
		// conteneur
	}

	@Command(name = "status", description = "Affiche les écarts entre les fichiers de droits et Synology Photos, sans rien modifier.")
	static final class Status extends AbstractCommand {
		@Override
		void execute() {
			final RightEngine re = new RightEngine(loadPrefs());
			re.getFullState();
			final SyncPlan plan = re.planPush();
			RightReports.printWarnings(plan);
			RightReports.printStatus(plan);
		}
	}

	/**
	 * Options communes aux synchros complètes.
	 */
	abstract static class FullSync extends AbstractCommand {
		@Option(names = { "-y", "--yes" }, description = "Applique sans demander de confirmation.")
		boolean yes;

		@Option(names = { "-n", "--dry-run" }, description = "Affiche les modifications sans les appliquer.")
		boolean dryRun;
	}

	@Command(name = "pull", description = "Synology Photos -> fichiers : crée/supprime les fichiers .synoRight d'après les droits de Synology Photos.")
	static final class Pull extends FullSync {
		@Override
		void execute() {
			final RightEngine re = new RightEngine(loadPrefs());
			re.getFullState();
			final SyncPlan plan = re.planPull();
			RightReports.printWarnings(plan);
			LogUtil.runMonitored(() -> RightReports.confirmAndApply(re, plan, yes, dryRun), "Action time");
		}
	}

	@Command(name = "push", description = "Fichiers -> Synology Photos : applique dans Synology Photos les droits décrits par les fichiers .synoRight.")
	static final class Push extends FullSync {
		@Override
		void execute() {
			final RightEngine re = new RightEngine(loadPrefs());
			re.getFullState();
			final SyncPlan plan = re.planPush();
			RightReports.printWarnings(plan);
			LogUtil.runMonitored(() -> RightReports.confirmAndApply(re, plan, yes, dryRun), "Action time");
		}
	}

	@Command(name = "show", description = "Affiche les droits d'un dossier côté Synology Photos et côté fichiers.")
	static final class Show extends AbstractCommand {
		@Parameters(index = "0", arity = "0..1", paramLabel = "<dossier>", defaultValue = ".", description = "Dossier (défaut : répertoire courant).")
		String folder;

		@Override
		void execute() {
			final RightEngine re = new RightEngine(loadPrefs());
			re.checkFolder(folder); // avant toute connexion
			re.login();
			RightReports.printFolder(re.folderRights(folder));
		}
	}

	@Command(name = "grant", description = "Donne le droit à un groupe sur un dossier (et sur son dossier parent).")
	static final class Grant extends AbstractCommand {
		@Parameters(index = "0", paramLabel = "<groupe>", description = "Nom du groupe (tel que configuré).")
		String group;

		@Parameters(index = "1", arity = "0..1", paramLabel = "<dossier>", defaultValue = ".", description = "Dossier (défaut : répertoire courant).")
		String folder;

		@Override
		void execute() {
			final Preferences pref = loadPrefs();
			final Group grp = pref.getGroupByName(group); // vérifié avant toute connexion
			final RightEngine re = new RightEngine(pref);
			re.checkFolder(folder); // avant toute connexion
			re.login();
			LogUtil.runMonitored(() -> System.out.println(re.addRightToFolder(folder, grp)),"Action time");
		}
	}

	@Command(name = "revoke", description = "Retire le droit d'un groupe sur un dossier.")
	static final class Revoke extends AbstractCommand {
		@Parameters(index = "0", paramLabel = "<groupe>", description = "Nom du groupe (tel que configuré).")
		String group;

		@Parameters(index = "1", arity = "0..1", paramLabel = "<dossier>", defaultValue = ".", description = "Dossier (défaut : répertoire courant).")
		String folder;

		@Override
		void execute() {
			final Preferences pref = loadPrefs();
			final Group grp = pref.getGroupByName(group); // vérifié avant toute connexion
			final RightEngine re = new RightEngine(pref);
			re.checkFolder(folder); // avant toute connexion
			re.login();
			LogUtil.runMonitored(() -> System.out.println(re.removeRightToFolder(folder, grp)),"Action time");
		}
	}
}
