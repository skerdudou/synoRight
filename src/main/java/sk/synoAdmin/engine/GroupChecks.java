package sk.synoAdmin.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import sk.synoAdmin.synology.SynoGroup;

/**
 * Cohérence entre les groupes de la configuration et ceux du NAS.
 */
public final class GroupChecks {

	private GroupChecks() {
		// utilitaire
	}

	/**
	 * Signale les groupes configurés absents du NAS ou renommés.
	 *
	 * @return les avertissements, vide si tout correspond
	 */
	public static List<String> warnings(final List<SynoGroup> nasGroups, final List<Group> groups) {
		final List<String> warnings = new ArrayList<>();
		for (final Group grp : groups) {
			final Optional<SynoGroup> nasGroup = nasGroups.stream()
					.filter(g -> String.valueOf(g.gid()).equals(grp.getId()))
					.findAny();

			if (nasGroup.isEmpty()) {
				warnings.add("Attention : le groupe '" + grp.getName() + "' (id " + grp.getId() + ") n'existe plus sur le NAS.");
			} else if (!nasGroup.get().name().equalsIgnoreCase(grp.getName())) {
				warnings.add("Attention : le groupe d'id " + grp.getId() + " s'appelle '" + nasGroup.get().name()
						+ "' sur le NAS, mais '" + grp.getName() + "' dans la configuration.");
			}
		}
		return warnings;
	}
}
