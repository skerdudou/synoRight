package sk.synoAdmin.cli;

import java.util.List;
import java.util.Map;

import sk.synoAdmin.ConsoleUtil;
import sk.synoAdmin.engine.DeltaResolver.GrpDelta;
import sk.synoAdmin.engine.Folder;
import sk.synoAdmin.engine.FolderRights;
import sk.synoAdmin.engine.Group;
import sk.synoAdmin.engine.RightEngine;
import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.engine.SyncPlan;

/**
 * Affichage console des résultats du moteur.
 */
final class RightReports {

	private RightReports() {
		// utility
	}

	static void printWarnings(final SyncPlan plan) {
		if (!plan.unindexedFolders().isEmpty()) {
			System.out.println(Messages.get("cli.report.unindexed", String.join(", ", plan.unindexedFolders())));
		}
		if (!plan.unknownFolders().isEmpty()) {
			System.out.println(Messages.get("cli.report.unknown", String.join(", ", plan.unknownFolders())));
		}
	}

	/** @param plan plan PUSH : ajouts = dans les fichiers seulement, suppressions = dans Photos seulement */
	static void printStatus(final SyncPlan plan) {
		if (plan.isEmpty()) {
			System.out.println(Messages.get("cli.report.synchronized", plan.folderCount()));
			return;
		}
		for (final Map.Entry<Group, GrpDelta> entry : plan.deltas().entrySet()) {
			System.out.println(Messages.get("cli.report.group", entry.getKey().getName()));
			printFolders(Messages.get("cli.report.inFilesOnly"), entry.getValue().getToAdd());
			printFolders(Messages.get("cli.report.inPhotosOnly"), entry.getValue().getToRemove());
		}
		System.out.println(Messages.get("cli.report.statusHint"));
	}

	private static void printFolders(final String message, final List<Folder> list) {
		if (!list.isEmpty()) {
			System.out.println("\t" + message);
			for (final Folder folder : list) {
				System.out.println("\t\t" + folder.getName());
			}
		}
	}

	static void confirmAndApply(final RightEngine engine, final SyncPlan plan, final boolean assumeYes, final boolean dryRun) {
		final boolean push = plan.direction() == SyncPlan.Direction.PUSH;
		System.out.println(Messages.get(push ? "cli.report.applyPush" : "cli.report.applyPull"));

		if (plan.isEmpty()) {
			System.out.println(Messages.get("cli.report.nothing"));
			return;
		}
		for (final Map.Entry<Group, GrpDelta> entry : plan.deltas().entrySet()) {
			System.out.println(Messages.get("cli.report.group", entry.getKey().getName()));
			entry.getValue().printAdd();
			entry.getValue().printRemove();
		}
		if (dryRun) {
			System.out.println(Messages.get("cli.report.dryRun"));
			return;
		}
		if (!assumeYes && !ConsoleUtil.promptBool(Messages.get("cli.report.confirm"))) {
			System.out.println(Messages.get("cli.report.aborted"));
			return;
		}

		final int count = engine.apply(plan);
		System.out.println(push ? Messages.get("cli.report.donePush") : Messages.get("cli.report.donePull", count));
	}

	static void printFolder(final FolderRights rights) {
		System.out.println(Messages.get("cli.report.folder", rights.folderName()));
		for (final FolderRights.GroupRight right : rights.rights()) {
			System.out.println("\t" + Messages.get("cli.report.right", right.group().getName(),
					Messages.get(right.inPhotos() ? "cli.report.yes" : "cli.report.no"),
					Messages.get(right.inFile() ? "cli.report.yes" : "cli.report.no"),
					right.hasGap() ? "   " + Messages.get("cli.report.gap") : ""));
		}
		if (rights.hasGap()) {
			System.out.println(Messages.get("cli.report.gaps"));
		}
	}
}
