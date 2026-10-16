package sk.synoAdmin.engine;

import java.util.ArrayList;
import java.util.List;

import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.synology.SynoGroup;

/**
 * Cohérence entre les groupes de la configuration et ceux du NAS.
 * Le nom d'un groupe dans la configuration est un nom court, libre (il donne le nom des fichiers
 * @nom.synoRight) : il peut différer du nom du groupe sur le NAS, seul l'id compte.
 */
public final class GroupChecks {

	// interdits dans un nom de fichier Windows, plus les séparateurs de la configuration (id:nom;id:nom)
	private static final String FORBIDDEN = "\\/:*?\"<>|;";

	private GroupChecks() {
		// utilitaire
	}

	/**
	 * Signale les groupes configurés absents du NAS.
	 *
	 * @return les avertissements, vide si tout correspond
	 */
	public static List<String> warnings(final List<SynoGroup> nasGroups, final List<Group> groups) {
		final List<String> warnings = new ArrayList<>();
		for (final Group grp : groups) {
			if (nasGroups.stream().noneMatch(g -> String.valueOf(g.gid()).equals(grp.getId()))) {
				warnings.add(Messages.get("engine.group.missing", grp.getName(), grp.getId()));
			}
		}
		return warnings;
	}

	/**
	 * Vérifie un nom court de groupe.
	 *
	 * @param groups groupes gérés (le nom ne doit pas être celui d'un autre groupe, sans tenir compte de la casse)
	 * @param id id du groupe nommé (ignoré dans la recherche de doublon)
	 * @return la raison du refus, null si le nom est valide
	 */
	public static String invalidShortName(final String name, final List<Group> groups, final String id) {
		final String trimmed = name == null ? "" : name.trim();
		if (trimmed.isEmpty()) {
			return Messages.get("engine.shortName.empty");
		}
		for (final char c : trimmed.toCharArray()) {
			if (FORBIDDEN.indexOf(c) >= 0 || c < ' ') {
				return Messages.get("engine.shortName.forbidden", c);
			}
		}
		if (groups.stream().anyMatch(g -> !g.getId().equals(id) && g.getName().equalsIgnoreCase(trimmed))) {
			return Messages.get("engine.shortName.duplicate");
		}
		return null;
	}
}
