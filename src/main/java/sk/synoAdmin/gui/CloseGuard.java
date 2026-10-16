package sk.synoAdmin.gui;

import java.awt.Component;

import javax.swing.JOptionPane;

/**
 * Garde de fermeture d'une fenêtre : tant qu'une écriture est en cours, la fermeture est refusée
 * (fermer tuerait l'opération à mi-chemin). Les chargements et analyses, eux, restent annulables.
 * Sur l'EDT uniquement.
 */
final class CloseGuard {

	static final String BUSY_MESSAGE = "Opération en cours, veuillez patienter.";

	private int writes;

	/** Une écriture démarre. */
	void begin() {
		writes++;
	}

	/** Une écriture se termine (succès ou échec). */
	void end() {
		if (writes > 0) {
			writes--;
		}
	}

	boolean isBusy() {
		return writes > 0;
	}

	/**
	 * Demande de fermeture : exécute onBusy si une écriture est en cours, sinon onFree.
	 *
	 * @return true si la fenêtre peut être fermée
	 */
	boolean requestClose(final Runnable onBusy, final Runnable onFree) {
		if (isBusy()) {
			onBusy.run();
			return false;
		}
		onFree.run();
		return true;
	}

	/** Fermeture d'une fenêtre : message d'attente si une écriture est en cours, sinon dispose. */
	void close(final java.awt.Window window) {
		requestClose(() -> notifyBusy(window), window::dispose);
	}

	private static void notifyBusy(final Component parent) {
		JOptionPane.showMessageDialog(parent, BUSY_MESSAGE, Gui.TITLE, JOptionPane.INFORMATION_MESSAGE);
	}
}
