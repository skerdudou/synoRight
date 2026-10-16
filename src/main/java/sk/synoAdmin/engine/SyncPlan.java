package sk.synoAdmin.engine;

import java.util.List;
import java.util.Map;

import sk.synoAdmin.engine.DeltaResolver.GrpDelta;

/**
 * Modifications à appliquer pour synchroniser fichiers et Synology Photos dans un sens.
 *
 * @param deltas par groupe géré (ordre de la configuration), sans les groupes déjà synchronisés
 * @param folderCount nombre de dossiers comparés
 * @param unindexedFolders dossiers du partage pas encore indexés par Synology Photos (ignorés)
 * @param unknownFolders dossiers connus de Synology Photos mais absents du partage (ignorés)
 */
public record SyncPlan(Direction direction, Map<Group, GrpDelta> deltas, int folderCount, List<String> unindexedFolders, List<String> unknownFolders) {

	/** PUSH : fichiers → Synology Photos ; PULL : Synology Photos → fichiers. */
	public enum Direction {
		PUSH, PULL
	}

	public boolean isEmpty() {
		return deltas.isEmpty();
	}

	public int changeCount() {
		return deltas.values().stream().mapToInt(d -> d.getToAdd().size() + d.getToRemove().size()).sum();
	}
}
