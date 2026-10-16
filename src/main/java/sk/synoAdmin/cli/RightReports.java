package sk.synoAdmin.cli;

import java.util.List;
import java.util.Map;

import sk.synoAdmin.ConsoleUtil;
import sk.synoAdmin.engine.DeltaResolver.GrpDelta;
import sk.synoAdmin.engine.Folder;
import sk.synoAdmin.engine.FolderRights;
import sk.synoAdmin.engine.Group;
import sk.synoAdmin.engine.RightEngine;
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
			System.out.println("Attention, dossiers pas encore indexés par Synology Photos (ignorés) : " + String.join(", ", plan.unindexedFolders()));
		}
		if (!plan.unknownFolders().isEmpty()) {
			System.out.println("Attention, dossiers connus de Synology Photos mais absents du partage (ignorés) : " + String.join(", ", plan.unknownFolders()));
		}
	}

	/** @param plan plan PUSH : ajouts = dans les fichiers seulement, suppressions = dans Photos seulement */
	static void printStatus(final SyncPlan plan) {
		if (plan.isEmpty()) {
			System.out.println("Fichiers de droits et Synology Photos sont synchronisés (" + plan.folderCount() + " dossiers).");
			return;
		}
		for (final Map.Entry<Group, GrpDelta> entry : plan.deltas().entrySet()) {
			System.out.println("Groupe '" + entry.getKey().getName() + "' :");
			printFolders("Dans les fichiers mais pas dans Synology Photos :", entry.getValue().getToAdd());
			printFolders("Dans Synology Photos mais pas dans les fichiers :", entry.getValue().getToRemove());
		}
		System.out.println("-> 'push' applique les fichiers dans Synology Photos, 'pull' recrée les fichiers d'après Synology Photos.");
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
		System.out.println(push ? "Mise à jour des droits dans Synology Photos d'après les fichiers."
				: "Mise à jour des fichiers de droits d'après Synology Photos.");

		if (plan.isEmpty()) {
			System.out.println("Aucune modification à appliquer.");
			return;
		}
		for (final Map.Entry<Group, GrpDelta> entry : plan.deltas().entrySet()) {
			System.out.println("Groupe '" + entry.getKey().getName() + "' :");
			entry.getValue().printAdd();
			entry.getValue().printRemove();
		}
		if (dryRun) {
			System.out.println("Simulation : aucune modification effectuée.");
			return;
		}
		if (!assumeYes && !ConsoleUtil.promptBool("Appliquer ces modifications ?")) {
			System.out.println("Abandon, aucune modification effectuée.");
			return;
		}

		final int count = engine.apply(plan);
		System.out.println(push ? "Droits Synology Photos mis à jour." : count + " fichier(s) de droits mis à jour.");
	}

	static void printFolder(final FolderRights rights) {
		System.out.println("Dossier " + rights.folderName() + " :");
		for (final FolderRights.GroupRight right : rights.rights()) {
			System.out.println("\t" + right.group().getName() + " : Synology Photos " + (right.inPhotos() ? "oui" : "non")
					+ ", fichier " + (right.inFile() ? "oui" : "non") + (right.hasGap() ? "   <- écart" : ""));
		}
		if (rights.hasGap()) {
			System.out.println("Écarts détectés : 'push' ou 'pull' pour resynchroniser.");
		}
	}
}
