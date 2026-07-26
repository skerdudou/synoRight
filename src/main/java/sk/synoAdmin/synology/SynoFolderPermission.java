package sk.synoAdmin.synology;

import java.util.List;

/**
 * Dossier de Synology Photos avec les ids des groupes autorisés à le voir.
 */
public record SynoFolderPermission(int id, String name, List<Integer> browseGrpIds) {
}
