package sk.synoAdmin.gui;

import java.awt.BorderLayout;
import java.awt.Dialog;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;

import sk.synoAdmin.LogUtil;
import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.menu.MenuRepair;

/**
 * Tâches de démarrage de l'IHM, avant l'ouverture de la première fenêtre.
 */
final class GuiStartup {

	private GuiStartup() {
		// utility
	}

	/** Initialisation commune à toutes les sous-commandes graphiques : Swing, puis réparation éventuelle des menus. */
	static void setupGui() {
		Gui.setup();
		repairMenus();
	}

	/**
	 * Répare les menus de l'Explorateur si l'application a changé (mise à jour, déplacement) depuis leur installation :
	 * petite fenêtre sans bouton pendant la réparation (hors EDT), puis rend la main. Un échec est signalé par la boîte
	 * d'erreur standard et ne bloque pas la suite ; rien n'est affiché si aucune réparation n'est nécessaire.
	 */
	static void repairMenus() {
		final JDialog[] window = new JDialog[1];
		boolean repairing = false;
		RuntimeException failure = null;
		try {
			if (!MenuRepair.isNeeded()) {
				return;
			}
			repairing = true;
			window[0] = Gui.onEdt(GuiStartup::createWindow);
			Gui.onEdt(() -> {
				window[0].setVisible(true);
				return null;
			});
			MenuRepair.repair(MenuRepair.Mode.STARTUP);
		} catch (final RuntimeException e) {
			failure = e;
		} finally {
			if (window[0] != null) {
				Gui.onEdt(() -> {
					window[0].dispose();
					return null;
				});
			}
		}
		if (failure != null) {
			LogUtil.debug(failure.toString());
			if (!repairing) {
				// échec de la simple vérification : journalisé, le démarrage continue sans message
				return;
			}
			final String message = Messages.get("gui.startup.repairFailed",
					failure.getMessage() != null ? failure.getMessage() : failure.toString());
			Gui.onEdt(() -> {
				Gui.showError(null, message, null);
				return null;
			});
		}
	}

	/** Fenêtre non modale, sans bouton : texte et barre de progression indéterminée. */
	private static JDialog createWindow() {
		final JDialog dialog = new JDialog((Dialog) null, Gui.TITLE, false);
		dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
		dialog.setIconImages(Gui.icons());
		dialog.setResizable(false);
		final JProgressBar progress = new JProgressBar();
		progress.setIndeterminate(true);
		final JPanel content = new JPanel(new BorderLayout(0, 12));
		content.setBorder(BorderFactory.createEmptyBorder(16, 24, 16, 24));
		content.add(new JLabel(Messages.get("gui.startup.repairingMenus")), BorderLayout.NORTH);
		content.add(progress, BorderLayout.CENTER);
		dialog.setContentPane(content);
		dialog.pack();
		dialog.setLocationRelativeTo(null);
		return dialog;
	}
}
