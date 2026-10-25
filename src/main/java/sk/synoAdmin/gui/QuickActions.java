package sk.synoAdmin.gui;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

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

	// au-delà, confirmation avant d'ouvrir un onglet par dossier
	static final int MAX_TABS_WITHOUT_CONFIRM = 5;

	private QuickActions() {
		// utility
	}

	/** Donne le droit du groupe sur les dossiers ; un dossier en erreur n'empêche pas le traitement des autres. */
	static void grant(final Preferences pref, final String group, final List<String> folders) {
		final Group grp = pref.getGroupByName(group);
		final RightEngine engine = loggedEngine(pref, folders);
		ConsoleUtil.info(String.join("\n", RightEngine.forEachFolder(folders, f -> List.of(engine.addRightToFolder(f, grp)))));
	}

	/** Retire le droit du groupe sur les dossiers ; un dossier en erreur n'empêche pas le traitement des autres. */
	static void revoke(final Preferences pref, final String group, final List<String> folders) {
		final Group grp = pref.getGroupByName(group);
		final RightEngine engine = loggedEngine(pref, folders);
		ConsoleUtil.info(String.join("\n", RightEngine.forEachFolder(folders, f -> List.of(engine.removeRightToFolder(f, grp)))));
	}

	/** Ouvre chaque dossier dans le navigateur, sur l'espace partagé de Synology Photos. */
	static void open(final Preferences pref, final List<String> folders) {
		if (folders.size() > MAX_TABS_WITHOUT_CONFIRM && !ConsoleUtil.promptBool(Messages.get("gui.open.confirmMany", folders.size()))) {
			return;
		}
		final RightEngine engine = loggedEngine(pref, folders);
		if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
			throw new UserException(Messages.get("gui.common.browserFailed", pref.getUrl()));
		}
		final List<String> errors = RightEngine.forEachFolder(folders, f -> {
			final String url = photosFolderUrl(pref.getUrl(), engine.folderId(f));
			try {
				Desktop.getDesktop().browse(URI.create(url));
			} catch (final IOException e) {
				throw new UserException(Messages.get("gui.common.browserFailed", url), e);
			}
			return List.of();
		});
		if (!errors.isEmpty()) {
			throw new UserException(String.join("\n", errors));
		}
	}

	// dossiers vérifiés avant toute connexion : erreur s'il n'y en a aucun de gérable, les autres sont signalés par forEachFolder
	private static RightEngine loggedEngine(final Preferences pref, final List<String> folders) {
		final RightEngine engine = new RightEngine(pref);
		engine.checkFolders(folders, new ArrayList<>());
		engine.login();
		return engine;
	}

	/** URL du dossier dans l'espace partagé de Synology Photos. */
	static String photosFolderUrl(final String baseUrl, final int folderId) {
		final String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		return base + "/photo/#/shared_space/folder/" + folderId;
	}
}
