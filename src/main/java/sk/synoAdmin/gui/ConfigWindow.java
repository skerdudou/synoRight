package sk.synoAdmin.gui;

import java.awt.Window;
import java.nio.file.Path;

import sk.synoAdmin.UserException;

/**
 * Fenêtre de configuration (squelette, remplacé par une tâche ultérieure).
 */
final class ConfigWindow {

	private ConfigWindow() {
		// utility
	}

	static Window create(final Path configDir) {
		throw new UserException("Fenêtre pas encore disponible.");
	}
}
