package sk.synoAdmin.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class DeltaResolver {

	public static final class GrpDelta {
		private final List<Folder> toAdd = new ArrayList<>();
		private final List<Folder> toRemove = new ArrayList<>();

		public List<Folder> getToAdd() {
			return toAdd;
		}

		public List<Folder> getToRemove() {
			return toRemove;
		}

		public void printAndProcessAdd(final Consumer<Folder> action) {
			doPrintAndProcess(toAdd, "Ajout des droits pour les répertoires :", action);
		}

		public void printAndProcessRemove(final Consumer<Folder> action) {
			doPrintAndProcess(toRemove, "Suppression des droits pour les répertoires :", action);
		}

		private void doPrintAndProcess(final List<Folder> list, final String message, final Consumer<Folder> action) {
			if (list.size() > 0) {
				System.out.println("\t" + message);

				for (final Folder folder : list) {
					System.out.println("\t\t" + folder.getName());

					if (action != null) {
						action.accept(folder);
					}
				}
			}
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
			doAddToDeltaMap(map, folder, grp, GrpDelta::getToRemove);
		}
		for (final Group grp : grpToAdd) {
			doAddToDeltaMap(map, folder, grp, GrpDelta::getToAdd);
		}
	}

	// ajoute le folder dans le bon attribut du delta de la map (créé ce qu'il faut
	// si nécessiare)
	private static void doAddToDeltaMap(final Map<Group, GrpDelta> map, final Folder folder, final Group grp, final Function<GrpDelta, List<Folder>> deltaGrpAccessor) {
		final GrpDelta delta;
		if (map.containsKey(grp)) {
			delta = map.get(grp);
		} else {
			delta = new GrpDelta();
			map.put(grp, delta);
		}

		deltaGrpAccessor.apply(delta).add(folder);
	}
}
