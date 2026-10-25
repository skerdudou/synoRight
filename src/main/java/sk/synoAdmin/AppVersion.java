package sk.synoAdmin;

import java.util.Optional;

/**
 * Version de l'application (Implementation-Version du manifeste) et comparaison de numéros de version.
 */
public final class AppVersion {

	private AppVersion() {
		// utility
	}

	/** Version de l'application ; vide en développement (hors jar). */
	public static Optional<String> current() {
		return Optional.ofNullable(AppVersion.class.getPackage().getImplementationVersion());
	}

	/**
	 * Compare deux versions : segments séparés par « . » comparés numériquement (segment absent = 0) ; un suffixe après
	 * le premier « - » rend la version inférieure à la même sans suffixe ; un segment non numérique passe après les
	 * numériques et se compare en texte.
	 */
	public static int compare(final String a, final String b) {
		final String[] partsA = a.split("-", 2);
		final String[] partsB = b.split("-", 2);
		final String[] segmentsA = partsA[0].split("\\.");
		final String[] segmentsB = partsB[0].split("\\.");
		for (int i = 0; i < Math.max(segmentsA.length, segmentsB.length); i++) {
			final int result = compareSegment(i < segmentsA.length ? segmentsA[i] : "0", i < segmentsB.length ? segmentsB[i] : "0");
			if (result != 0) {
				return result;
			}
		}
		final boolean suffixA = partsA.length > 1;
		final boolean suffixB = partsB.length > 1;
		if (suffixA != suffixB) {
			return suffixA ? -1 : 1;
		}
		return suffixA ? Integer.signum(partsA[1].compareTo(partsB[1])) : 0;
	}

	private static int compareSegment(final String a, final String b) {
		final boolean numericA = isNumeric(a);
		final boolean numericB = isNumeric(b);
		if (numericA && numericB) {
			return Integer.signum(Long.compare(Long.parseLong(a), Long.parseLong(b)));
		}
		if (numericA != numericB) {
			return numericA ? -1 : 1;
		}
		return Integer.signum(a.compareTo(b));
	}

	private static boolean isNumeric(final String segment) {
		return !segment.isEmpty() && segment.length() <= 18 && segment.chars().allMatch(c -> c >= '0' && c <= '9');
	}
}
