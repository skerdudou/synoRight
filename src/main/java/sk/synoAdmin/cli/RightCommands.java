package sk.synoAdmin.cli;

import java.util.List;

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

	@Command(name = "status")
	static final class Status extends AbstractCommand {
		@Override
		protected void execute() {
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
		@Option(names = { "-y", "--yes" }, descriptionKey = "cli.opt.yes")
		boolean yes;

		@Option(names = { "-n", "--dry-run" }, descriptionKey = "cli.opt.dryRun")
		boolean dryRun;
	}

	@Command(name = "pull")
	static final class Pull extends FullSync {
		@Override
		protected void execute() {
			final RightEngine re = new RightEngine(loadPrefs());
			re.getFullState();
			final SyncPlan plan = re.planPull();
			RightReports.printWarnings(plan);
			LogUtil.runMonitored(() -> RightReports.confirmAndApply(re, plan, yes, dryRun), "Action time");
		}
	}

	@Command(name = "push")
	static final class Push extends FullSync {
		@Override
		protected void execute() {
			final RightEngine re = new RightEngine(loadPrefs());
			re.getFullState();
			final SyncPlan plan = re.planPush();
			RightReports.printWarnings(plan);
			LogUtil.runMonitored(() -> RightReports.confirmAndApply(re, plan, yes, dryRun), "Action time");
		}
	}

	@Command(name = "show")
	static final class Show extends AbstractCommand {
		@Parameters(index = "0..*", arity = "0..*", paramLabel = "<folder>", defaultValue = ".", descriptionKey = "cli.param.folder")
		List<String> folders;

		@Override
		protected void execute() {
			final RightEngine re = new RightEngine(loadPrefs());
			folders.forEach(re::checkFolder); // avant toute connexion
			re.login();
			re.folderRights(folders).forEach(RightReports::printFolder);
		}
	}

	@Command(name = "grant")
	static final class Grant extends AbstractCommand {
		@Parameters(index = "0", paramLabel = "<group>", descriptionKey = "cli.param.group")
		String group;

		@Parameters(index = "1..*", arity = "0..*", paramLabel = "<folder>", defaultValue = ".", descriptionKey = "cli.param.folder")
		List<String> folders;

		@Override
		protected void execute() {
			final Preferences pref = loadPrefs();
			final Group grp = pref.getGroupByName(group); // vérifié avant toute connexion
			final RightEngine re = new RightEngine(pref);
			folders.forEach(re::checkFolder); // avant toute connexion
			re.login();
			LogUtil.runMonitored(() -> folders.forEach(f -> System.out.println(re.addRightToFolder(f, grp))), "Action time");
		}
	}

	@Command(name = "revoke")
	static final class Revoke extends AbstractCommand {
		@Parameters(index = "0", paramLabel = "<group>", descriptionKey = "cli.param.group")
		String group;

		@Parameters(index = "1..*", arity = "0..*", paramLabel = "<folder>", defaultValue = ".", descriptionKey = "cli.param.folder")
		List<String> folders;

		@Override
		protected void execute() {
			final Preferences pref = loadPrefs();
			final Group grp = pref.getGroupByName(group); // vérifié avant toute connexion
			final RightEngine re = new RightEngine(pref);
			folders.forEach(re::checkFolder); // avant toute connexion
			re.login();
			LogUtil.runMonitored(() -> folders.forEach(f -> System.out.println(re.removeRightToFolder(f, grp))), "Action time");
		}
	}
}
