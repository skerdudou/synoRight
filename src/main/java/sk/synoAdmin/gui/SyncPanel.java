package sk.synoAdmin.gui;

import java.awt.Window;

import sk.synoAdmin.UserException;
import sk.synoAdmin.engine.SyncPlan;
import sk.synoAdmin.prefs.Preferences;

/**
 * Fenêtre de synchronisation (squelette, remplacé par une tâche ultérieure).
 */
final class SyncPanel {

	private SyncPanel() {
		// utility
	}

	static Window createWindow(final Preferences pref, final SyncPlan.Direction direction) {
		throw new UserException("Fenêtre pas encore disponible.");
	}
}
