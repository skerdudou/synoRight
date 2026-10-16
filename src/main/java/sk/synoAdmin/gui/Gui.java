package sk.synoAdmin.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import sk.synoAdmin.ConsoleUtil;
import sk.synoAdmin.UserException;
import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.menu.ProcessRunner;

/**
 * Outils communs aux fenêtres : thème, attente de fermeture, tâches en arrière-plan, erreurs.
 */
public final class Gui {

	public static final String TITLE = "synoRight";

	/** Composants désactivés par des tâches en cours (EDT seulement ; Component : égalité par identité). */
	private static final Map<Component, DisableLock> DISABLED = new WeakHashMap<>();

	private Gui() {
		// utility
	}

	/** Thème FlatLaf (clair ou sombre comme Windows) et dialogues pour les saisies. */
	public static void setup() {
		if (GraphicsEnvironment.isHeadless()) {
			throw new UserException(Messages.get("gui.noDisplay"));
		}
		if (windowsDarkTheme()) {
			FlatDarkLaf.setup();
		} else {
			FlatLightLaf.setup();
		}
		applyLocale(Messages.language().locale());
		ConsoleUtil.setInteraction(new SwingInteraction());
	}

	/** Textes standard de Swing (Oui/Non, sélecteur de fichiers) dans la langue choisie ; formats de nombres et dates inchangés. */
	static void applyLocale(final Locale locale) {
		Locale.setDefault(Locale.Category.DISPLAY, locale);
		JComponent.setDefaultLocale(locale);
		UIManager.getDefaults().setDefaultLocale(locale);
	}

	private static boolean windowsDarkTheme() {
		if (!ProcessRunner.isWindows()) {
			return false;
		}
		final ProcessRunner.Result result = ProcessRunner.run(List.of("reg", "query",
				"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme"));
		return result.ok() && result.output().contains("0x0");
	}

	/**
	 * Affiche la fenêtre et attend sa fermeture (dispose). Les fenêtres doivent utiliser DISPOSE_ON_CLOSE
	 * (ou appeler elles-mêmes dispose() à la fermeture), sinon l'attente ne se termine jamais.
	 * Si l'affichage échoue, l'erreur est présentée, la fenêtre libérée et l'attente levée.
	 */
	public static void showAndWait(final Window window) {
		final CountDownLatch closed = new CountDownLatch(1);
		window.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(final WindowEvent e) {
				closed.countDown();
			}
		});
		EventQueue.invokeLater(() -> {
			try {
				window.setLocationRelativeTo(null);
				window.setVisible(true);
			} catch (final Throwable e) {
				try {
					showError(null, e);
				} finally {
					window.dispose();
					closed.countDown();
				}
			}
		});
		try {
			closed.await();
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Exécute le code sur l'EDT et renvoie son résultat ; une exception du code (UserException comprise)
	 * est relancée telle quelle à l'appelant.
	 */
	static <T> T onEdt(final Supplier<T> code) {
		if (EventQueue.isDispatchThread()) {
			return code.get();
		}
		final AtomicReference<T> result = new AtomicReference<>();
		try {
			EventQueue.invokeAndWait(() -> result.set(code.get()));
		} catch (final InvocationTargetException e) {
			if (e.getCause() instanceof final RuntimeException cause) {
				throw cause;
			}
			if (e.getCause() instanceof final Error cause) {
				throw cause;
			}
			throw new RuntimeException(e.getCause());
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new UserException(Messages.get("ui.input.cancelled"));
		}
		return result.get();
	}

	/** Voir {@link #runAsync(Component, Supplier, Consumer, Runnable)}, sans traitement particulier de l'échec. */
	public static <T> void runAsync(final Component owner, final Supplier<T> task, final Consumer<T> onSuccess) {
		runAsync(owner, task, onSuccess, () -> {
			// rien
		});
	}

	/**
	 * Exécute une tâche longue (réseau, disque) hors de l'EDT : curseur d'attente, contenu de l'owner
	 * désactivé pendant l'opération, résultat ou erreur traités sur l'EDT. En cas d'échec, l'erreur est
	 * affichée puis onFailure est exécuté.
	 */
	public static <T> void runAsync(final Component owner, final Supplier<T> task, final Consumer<T> onSuccess,
			final Runnable onFailure) {
		final List<Component> disabled = disable(owner);
		owner.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
		new SwingWorker<T, Void>() {
			@Override
			protected T doInBackground() {
				return task.get();
			}

			@Override
			protected void done() {
				restore(disabled);
				owner.setCursor(Cursor.getDefaultCursor());
				try {
					final T result = get();
					try {
						onSuccess.accept(result);
					} catch (final RuntimeException e) {
						showError(owner, e);
						onFailure.run();
					}
				} catch (final ExecutionException e) {
					showError(owner, e.getCause());
					onFailure.run();
				} catch (final CancellationException e) {
					onFailure.run();
				} catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}.execute();
	}

	/**
	 * Désactive le composant et tout son contenu (setEnabled seul ne touche pas aux enfants d'une fenêtre).
	 * Réentrant : des tâches qui se chevauchent se composent, l'état d'origine n'est rétabli qu'à la dernière
	 * restauration. Sur l'EDT uniquement.
	 *
	 * @return les composants désactivés, à passer à {@link #restore(List)}
	 */
	static List<Component> disable(final Component component) {
		final List<Component> acquired = new ArrayList<>();
		disable(component, acquired);
		return acquired;
	}

	private static void disable(final Component component, final List<Component> acquired) {
		final DisableLock lock = DISABLED.computeIfAbsent(component, c -> new DisableLock(c.isEnabled()));
		lock.count++;
		component.setEnabled(false);
		acquired.add(component);
		if (component instanceof final Container container) {
			for (final Component child : container.getComponents()) {
				disable(child, acquired);
			}
		}
	}

	/** Libère une désactivation ; un composant retrouve son état d'origine quand plus aucune tâche ne le désactive. */
	static void restore(final List<Component> acquired) {
		for (final Component component : acquired) {
			final DisableLock lock = DISABLED.get(component);
			if (lock != null && --lock.count == 0) {
				DISABLED.remove(component);
				component.setEnabled(lock.originalEnabled);
			}
		}
	}

	/** Désactivations en cours d'un composant et son état avant la première. */
	private static final class DisableLock {
		private final boolean originalEnabled;
		private int count;

		DisableLock(final boolean originalEnabled) {
			this.originalEnabled = originalEnabled;
		}
	}

	/** Erreur prévue (UserException) : message seul ; sinon message + détail repliable. */
	public static void showError(final Component parent, final Throwable e) {
		if (e instanceof UserException) {
			JOptionPane.showMessageDialog(parent, e.getMessage(), TITLE, JOptionPane.ERROR_MESSAGE);
			return;
		}
		showError(parent, Messages.get("main.error.unexpected", e), e);
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
		final JCheckBox toggle = new JCheckBox(Messages.get("gui.common.showDetails"));
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
