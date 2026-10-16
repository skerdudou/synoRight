package sk.synoAdmin.gui;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

import sk.synoAdmin.ConsoleUtil;
import sk.synoAdmin.UserException;
import sk.synoAdmin.engine.Group;
import sk.synoAdmin.engine.RightEngine;
import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.prefs.Preferences;

/**
 * Actions rapides du menu contextuel : ajout/retrait d'un groupe sur un dossier, ouverture dans Synology Photos.
 */
final class QuickActions {

	private QuickActions() {
		// utility
	}

	/** Donne le droit du groupe sur le dossier. */
	static void grant(final Preferences pref, final String group, final String folder) {
		final Group grp = pref.getGroupByName(group);
		final RightEngine engine = new RightEngine(pref);
		engine.checkFolder(folder);
		engine.login();
		ConsoleUtil.info(engine.addRightToFolder(folder, grp));
	}

	/** Retire le droit du groupe sur le dossier. */
	static void revoke(final Preferences pref, final String group, final String folder) {
		final Group grp = pref.getGroupByName(group);
		final RightEngine engine = new RightEngine(pref);
		engine.checkFolder(folder);
		engine.login();
		ConsoleUtil.info(engine.removeRightToFolder(folder, grp));
	}

	/** Ouvre le dossier dans le navigateur, sur l'espace partagé de Synology Photos. */
	static void open(final Preferences pref, final String folder) {
		final RightEngine engine = new RightEngine(pref);
		engine.checkFolder(folder);
		engine.login();
		final String url = photosFolderUrl(pref.getUrl(), engine.folderId(folder));
		if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
			throw new UserException(Messages.get("gui.common.browserFailed", url));
		}
		try {
			Desktop.getDesktop().browse(URI.create(url));
		} catch (final IOException e) {
			throw new UserException(Messages.get("gui.common.browserFailed", url), e);
		}
	}

	/** URL du dossier dans l'espace partagé de Synology Photos. */
	static String photosFolderUrl(final String baseUrl, final int folderId) {
		final String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		return base + "/photo/#/shared_space/folder/" + folderId;
	}
}
