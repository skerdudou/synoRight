package sk.synoAdmin.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public final class DeltaResolver {

	private DeltaResolver() {
		// utility
	}

	public static final class GrpDelta {
		private final List<Folder> toAdd = new ArrayList<>();
		private final List<Folder> toRemove = new ArrayList<>();

		public List<Folder> getToAdd() {
			return toAdd;
		}

		public List<Folder> getToRemove() {
			return toRemove;
		}

		public void printAdd() {
			print(toAdd, "Ajout des droits pour les répertoires :");
		}

		public void printRemove() {
			print(toRemove, "Suppression des droits pour les répertoires :");
		}

		private void print(final List<Folder> list, final String message) {
			if (!list.isEmpty()) {
				System.out.println("\t" + message);

				for (final Folder folder : list) {
					System.out.println("\t\t" + folder.getName());
				}
			}
		}

		public void processAdd(final Consumer<Folder> action) {
			toAdd.forEach(action);
		}

		public void processRemove(final Consumer<Folder> action) {
			toRemove.forEach(action);
		}
	}

	public static Map<Group, GrpDelta> resolveDeltasFsToDb(final List<Folder> folders) {
		return resolveDeltas(folders, Folder::getDbGroups, Folder::getFsGroups);
	}

	public static Map<Group, GrpDelta> resolveDeltasDbToFs(final List<Folder> folders) {
		return resolveDeltas(folders, Folder::getFsGroups, Folder::getDbGroups);
	}

	private static Map<Group, GrpDelta> resolveDeltas(final List<Folder> folders, final Function<Folder, List<Group>> actualGroupsAccessor, final Function<Folder, List<Group>> targetGroupsAccessor) {
		final Map<Group, GrpDelta> retour = new HashMap<>();

		for (final Folder folder : folders) {
			// récupère les listes de groupes du dossier en fonction du contexte (db->fs ou
			// fs->db)
			final List<Group> actualGroups = actualGroupsAccessor.apply(folder);
			final List<Group> targetGroups = targetGroupsAccessor.apply(folder);

			// les groupes a enlever c'est actuel - cible
			final List<Group> grpToRemove = new ArrayList<>(actualGroups);
			grpToRemove.removeAll(targetGroups);

			// les groupes a ajouter c'est cible - actuel
			final List<Group> grpToAdd = new ArrayList<>(targetGroups);
			grpToAdd.removeAll(actualGroups);

			addToDeltaMap(retour, folder, grpToRemove, grpToAdd);
		}

		return retour;
	}

	// ajoute les groupes a ajouter et a supprimer dans les bons delta des groupes
	// de la map
	private static void addToDeltaMap(final Map<Group, GrpDelta> map, final Folder folder, final List<Group> grpToRemove, final List<Group> grpToAdd) {
		for (final Group grp : grpToRemove) {
			map.computeIfAbsent(grp, g -> new GrpDelta()).getToRemove().add(folder);
		}
		for (final Group grp : grpToAdd) {
			map.computeIfAbsent(grp, g -> new GrpDelta()).getToAdd().add(folder);
		}
	}
}
