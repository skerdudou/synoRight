package sk.synoAdmin.gui;

import sk.synoAdmin.UserException;
import sk.synoAdmin.prefs.Preferences;

/**
 * Actions rapides du menu contextuel, résultat en dialogue (squelette, remplacé par une tâche ultérieure).
 */
final class QuickActions {

	private QuickActions() {
		// utility
	}

	static void grant(final Preferences pref, final String group, final String folder) {
		throw new UserException("Fenêtre pas encore disponible.");
	}

	static void revoke(final Preferences pref, final String group, final String folder) {
		throw new UserException("Fenêtre pas encore disponible.");
	}

	static void open(final Preferences pref, final String folder) {
		throw new UserException("Fenêtre pas encore disponible.");
	}
}
