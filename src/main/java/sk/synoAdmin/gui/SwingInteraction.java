package sk.synoAdmin.gui;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;

import sk.synoAdmin.UserException;
import sk.synoAdmin.ui.Interaction;

/**
 * Saisies par dialogues Swing, appelables depuis n'importe quel thread (exécutées sur l'EDT).
 * Une annulation lève UserException("Saisie annulée.").
 */
final class SwingInteraction implements Interaction {

	private static final String CANCELLED = "Saisie annulée.";

	@Override
	public String promptLine(final String label) {
		while (true) {
			final String value = promptLine(label, null);
			if (!value.isEmpty()) {
				return value;
			}
		}
	}

	@Override
	public String promptLine(final String label, final String defaultValue) {
		final String value = Gui.onEdt(() -> (String) JOptionPane.showInputDialog(null, label, Gui.TITLE,
				JOptionPane.QUESTION_MESSAGE, null, null, defaultValue));
		if (value == null) {
			throw new UserException(CANCELLED);
		}
		return value.trim().isEmpty() && defaultValue != null ? defaultValue : value.trim();
	}

	@Override
	public List<Integer> promptChoices(final String label, final List<String> items) {
		while (true) {
			final List<Integer> choices = Gui.onEdt(() -> {
				final JPanel panel = new JPanel(new GridLayout(0, 1));
				panel.add(new JLabel(label));
				final List<JCheckBox> boxes = items.stream().map(JCheckBox::new).toList();
				boxes.forEach(panel::add);
				if (JOptionPane.showConfirmDialog(null, panel, Gui.TITLE, JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
					return null;
				}
				final List<Integer> selected = new ArrayList<>();
				for (int i = 0; i < boxes.size(); i++) {
					if (boxes.get(i).isSelected()) {
						selected.add(i);
					}
				}
				return selected;
			});
			if (choices == null) {
				throw new UserException(CANCELLED);
			}
			if (!choices.isEmpty()) {
				return choices;
			}
		}
	}

	@Override
	public String promptSecret(final String label) {
		while (true) {
			final String value = readSecret(label);
			if (!value.isBlank()) {
				return value;
			}
		}
	}

	@Override
	public String promptSecretOrKeep(final String label) {
		final String value = readSecret(label);
		return value.isBlank() ? null : value;
	}

	private static String readSecret(final String label) {
		final String value = Gui.onEdt(() -> {
			final JPasswordField field = new JPasswordField(20);
			final JPanel panel = new JPanel(new BorderLayout(0, 4));
			panel.add(new JLabel(label), BorderLayout.NORTH);
			panel.add(field, BorderLayout.CENTER);
			field.addAncestorListener(new AncestorListener() {
				@Override
				public void ancestorAdded(final AncestorEvent e) {
					field.requestFocusInWindow();
				}

				@Override
				public void ancestorRemoved(final AncestorEvent e) {
					// rien
				}

				@Override
				public void ancestorMoved(final AncestorEvent e) {
					// rien
				}
			});
			final int choice = JOptionPane.showConfirmDialog(null, panel, Gui.TITLE, JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
			return choice == JOptionPane.OK_OPTION ? new String(field.getPassword()) : null;
		});
		if (value == null) {
			throw new UserException(CANCELLED);
		}
		return value;
	}

	@Override
	public boolean promptBool(final String label) {
		return Gui.onEdt(() -> JOptionPane.showConfirmDialog(null, label, Gui.TITLE, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION);
	}

	@Override
	public void info(final String message) {
		Gui.onEdt(() -> {
			JOptionPane.showMessageDialog(null, message, Gui.TITLE, JOptionPane.INFORMATION_MESSAGE);
			return null;
		});
	}

	@Override
	public void error(final String message, final Throwable detail) {
		Gui.onEdt(() -> {
			Gui.showError(null, message, detail);
			return null;
		});
	}

	@Override
	public boolean isGraphical() {
		return true;
	}
}
