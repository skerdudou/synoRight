package sk.synoAdmin.gui;

import java.awt.Window;

import sk.synoAdmin.UserException;
import sk.synoAdmin.prefs.Preferences;

/**
 * Fenêtre des droits d'un dossier (squelette, remplacé par une tâche ultérieure).
 */
final class RightsWindow {

	private RightsWindow() {
		// utility
	}

	static Window create(final Preferences pref, final String folder) {
		throw new UserException("Fenêtre pas encore disponible.");
	}
}
