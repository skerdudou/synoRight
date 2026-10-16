package sk.synoAdmin.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableCellRenderer;

import sk.synoAdmin.engine.RightEngine;
import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.prefs.Preferences;

/**
 * Fenêtre « Droits du dossier » : une case par groupe géré, écarts Photos/fichiers signalés.
 */
final class RightsWindow {

	private RightsWindow() {
		// fabrique
	}

	/** Construit la fenêtre (sur l'EDT, sans accès réseau) ; les droits sont chargés à l'ouverture. */
	static JFrame create(final Preferences pref, final String folder) {
		new RightEngine(pref).checkFolder(folder); // avant toute connexion : erreur immédiate si hors du dossier photos

		final JFrame frame = new JFrame(Messages.get("gui.rights.windowTitle", Gui.TITLE));
		frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE); // fermeture via la garde, toujours terminée par dispose()
		final CloseGuard guard = new CloseGuard();
		final JLabel title = new JLabel(Messages.get("gui.rights.loading"));
		title.putClientProperty("FlatLaf.styleClass", "h3");
		final JTable table = new JTable();
		table.setDefaultRenderer(Object.class, new GapRenderer());
		final JLabel note = new JLabel(Messages.get("gui.rights.note"));
		final JButton apply = new JButton(Messages.get("gui.rights.apply"));
		final JButton close = new JButton(Messages.get("gui.common.close"));
		apply.setEnabled(false);
		close.addActionListener(e -> guard.close(frame));

		final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.add(apply);
		buttons.add(close);
		final JPanel bottom = new JPanel(new BorderLayout(8, 0));
		bottom.add(note, BorderLayout.CENTER);
		bottom.add(buttons, BorderLayout.EAST);
		final JPanel content = new JPanel(new BorderLayout(0, 8));
		content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		content.add(title, BorderLayout.NORTH);
		content.add(new JScrollPane(table), BorderLayout.CENTER);
		content.add(bottom, BorderLayout.SOUTH);
		frame.setContentPane(content);

		final Runnable[] load = new Runnable[1];
		// moteur neuf à chaque appel : il accumule les dossiers résolus
		load[0] = () -> Gui.runAsync(frame.getContentPane(), () -> {
			final RightEngine engine = new RightEngine(pref);
			engine.login();
			return engine.folderRights(folder);
		}, rights -> {
			title.setText(Messages.get("gui.rights.folder", rights.folderName()));
			final FolderRightsTableModel model = new FolderRightsTableModel(rights);
			table.setModel(model);
			apply.setEnabled(model.hasPendingChanges());
			model.addTableModelListener(e -> apply.setEnabled(model.hasPendingChanges()));
		}, frame::dispose);
		apply.addActionListener(e -> {
			final FolderRightsTableModel model = (FolderRightsTableModel) table.getModel();
			final var wanted = model.wantedGroups(); // lu sur l'EDT
			guard.begin(); // l'écriture ne doit pas être interrompue par la fermeture
			Gui.runAsync(frame.getContentPane(), () -> {
				final RightEngine engine = new RightEngine(pref);
				engine.login();
				return engine.setFolderRights(folder, wanted);
			}, messages -> {
				guard.end();
				if (!messages.isEmpty()) {
					JOptionPane.showMessageDialog(frame, String.join("\n", messages), Gui.TITLE, JOptionPane.INFORMATION_MESSAGE);
				}
				load[0].run();
			}, guard::end);
		});
		frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(final WindowEvent e) {
				guard.close(frame);
			}

			@Override
			public void windowOpened(final WindowEvent e) {
				load[0].run();
			}
		});
		frame.setSize(520, 360);
		frame.setLocationRelativeTo(null);
		return frame;
	}

	/** Écart Photos/fichier : ligne en couleur d'avertissement. */
	private static final class GapRenderer extends DefaultTableCellRenderer {
		private static final Color FALLBACK = new Color(0xE08A00);

		@Override
		public Component getTableCellRendererComponent(final JTable table, final Object value, final boolean selected,
				final boolean focus, final int row, final int column) {
			final Component c = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
			final boolean gap = table.getModel() instanceof FolderRightsTableModel model && model.hasGap(table.convertRowIndexToModel(row));
			if (gap && !selected) {
				final Color warning = UIManager.getColor("Component.warning.focusedBorderColor");
				c.setForeground(warning != null ? warning : FALLBACK);
			} else if (!selected) {
				c.setForeground(table.getForeground());
			}
			return c;
		}
	}
}
