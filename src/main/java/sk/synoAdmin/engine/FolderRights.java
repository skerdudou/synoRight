package sk.synoAdmin.engine;

import java.util.List;

/**
 * Droits d'un dossier pour chaque groupe géré, côté Synology Photos et côté fichiers.
 */
public record FolderRights(String folderName, List<GroupRight> rights) {

	public record GroupRight(Group group, boolean inPhotos, boolean inFile) {
		public boolean hasGap() {
			return inPhotos != inFile;
		}
	}

	public boolean hasGap() {
		return rights.stream().anyMatch(GroupRight::hasGap);
	}
}
