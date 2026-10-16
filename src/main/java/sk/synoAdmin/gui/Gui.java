package sk.synoAdmin.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import sk.synoAdmin.ConsoleUtil;
import sk.synoAdmin.UserException;
import sk.synoAdmin.menu.ProcessRunner;

/**
 * Outils communs aux fenêtres : thème, attente de fermeture, tâches en arrière-plan, erreurs.
 */
public final class Gui {

	public static final String TITLE = "synoRight";

	private Gui() {
		// utility
	}

	/** Thème FlatLaf (clair ou sombre comme Windows) et dialogues pour les saisies. */
	public static void setup() {
		if (GraphicsEnvironment.isHeadless()) {
			throw new UserException("Pas d'affichage graphique disponible : utiliser les commandes en console (synoRight --help).");
		}
		if (windowsDarkTheme()) {
			FlatDarkLaf.setup();
		} else {
			FlatLightLaf.setup();
		}
		ConsoleUtil.setInteraction(new SwingInteraction());
	}

	private static boolean windowsDarkTheme() {
		if (!ProcessRunner.isWindows()) {
			return false;
		}
		final ProcessRunner.Result result = ProcessRunner.run(List.of("reg", "query",
				"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme"));
		return result.ok() && result.output().contains("0x0");
	}

	/** Affiche la fenêtre et attend sa fermeture (dispose). */
	public static void showAndWait(final Window window) {
		final CountDownLatch closed = new CountDownLatch(1);
		window.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(final WindowEvent e) {
				closed.countDown();
			}
		});
		EventQueue.invokeLater(() -> {
			window.setLocationRelativeTo(null);
			window.setVisible(true);
		});
		try {
			closed.await();
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Exécute une tâche longue (réseau, disque) hors de l'EDT : curseur d'attente, owner désactivé
	 * pendant l'opération, résultat ou erreur traités sur l'EDT.
	 */
	public static <T> void runAsync(final Component owner, final Supplier<T> task, final Consumer<T> onSuccess) {
		owner.setEnabled(false);
		owner.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
		new SwingWorker<T, Void>() {
			@Override
			protected T doInBackground() {
				return task.get();
			}

			@Override
			protected void done() {
				owner.setEnabled(true);
				owner.setCursor(Cursor.getDefaultCursor());
				try {
					onSuccess.accept(get());
				} catch (final ExecutionException e) {
					showError(owner, e.getCause());
				} catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}.execute();
	}

	/** Erreur prévue (UserException) : message seul ; sinon message + détail repliable. */
	public static void showError(final Component parent, final Throwable e) {
		if (e instanceof UserException) {
			JOptionPane.showMessageDialog(parent, e.getMessage(), TITLE, JOptionPane.ERROR_MESSAGE);
			return;
		}
		showError(parent, "Erreur inattendue : " + e, e);
	}

	static void showError(final Component parent, final String message, final Throwable detail) {
		if (detail == null) {
			JOptionPane.showMessageDialog(parent, message, TITLE, JOptionPane.ERROR_MESSAGE);
			return;
		}
		final StringWriter trace = new StringWriter();
		detail.printStackTrace(new PrintWriter(trace));
		final JTextArea area = new JTextArea(trace.toString(), 15, 70);
		area.setEditable(false);
		final JScrollPane details = new JScrollPane(area);
		details.setVisible(false);
		final JCheckBox toggle = new JCheckBox("Afficher le détail");
		final JPanel panel = new JPanel(new BorderLayout(0, 8));
		panel.add(new JLabel(message), BorderLayout.NORTH);
		panel.add(toggle, BorderLayout.CENTER);
		panel.add(details, BorderLayout.SOUTH);
		toggle.addActionListener(ev -> {
			details.setVisible(toggle.isSelected());
			SwingUtilities.getWindowAncestor(panel).pack();
		});
		JOptionPane.showMessageDialog(parent, panel, TITLE, JOptionPane.ERROR_MESSAGE);
	}

	public static boolean confirm(final Component parent, final String message) {
		return JOptionPane.showConfirmDialog(parent, message, TITLE, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
	}
}
