package sk.synoAdmin.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;

import sk.synoAdmin.UserException;
import sk.synoAdmin.engine.FolderRights;
import sk.synoAdmin.engine.RightEngine;
import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.prefs.Preferences;

/**
 * Fenêtre « Droits du dossier ». Un dossier : une case par groupe géré, état Photos et fichier détaillé.
 * Plusieurs dossiers : un tableau dossiers × groupes, en-tête de colonne cliquable pour tout cocher.
 * Les écarts Photos/fichiers sont signalés en couleur.
 */
final class RightsWindow {

	private static final Color FALLBACK = new Color(0xE08A00);
	// au-delà, le compte rendu est affiché dans une zone défilante
	private static final int MAX_MESSAGE_LINES = 12;

	private RightsWindow() {
		// fabrique
	}

	/** Construit la fenêtre (sur l'EDT, sans accès réseau) ; les droits sont chargés à l'ouverture. */
	static JFrame create(final Preferences pref, final List<String> selection) {
		// avant toute connexion : dossiers hors du dossier photos écartés (erreur immédiate s'il n'en reste aucun)
		final List<String> skipped = new ArrayList<>();
		final List<String> folders = new RightEngine(pref).checkFolders(selection, skipped);
		final boolean single = folders.size() == 1;

		final JFrame frame = new JFrame(single ? Messages.get("gui.rights.windowTitle", Gui.TITLE)
				: Messages.get("gui.rights.windowTitleMulti", Gui.TITLE));
		frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE); // fermeture via la garde, toujours terminée par dispose()
		final CloseGuard guard = new CloseGuard();
		final JLabel title = new JLabel(Messages.get("gui.rights.loading"));
		title.putClientProperty("FlatLaf.styleClass", "h3");
		final JTable table = new JTable();
		table.setDefaultRenderer(Object.class, new GapRenderer());
		table.setDefaultRenderer(Boolean.class, new GapCheckRenderer(table.getDefaultRenderer(Boolean.class)));
		table.getTableHeader().setReorderingAllowed(false);
		// notes sur toute la largeur, une ligne chacune, au-dessus des boutons
		final JPanel notes = new JPanel(new GridLayout(0, 1, 0, 2));
		if (!single) {
			notes.add(new JLabel(Messages.get("gui.rights.noteColumn")));
		}
		notes.add(new JLabel(Messages.get("gui.rights.note")));
		final JButton apply = new JButton(Messages.get("gui.rights.apply"));
		final JButton close = new JButton(Messages.get("gui.common.close"));
		apply.setEnabled(false);
		close.addActionListener(e -> guard.close(frame));

		// plusieurs dossiers : clic sur l'en-tête d'un groupe = cocher/décocher toute la colonne
		table.getTableHeader().addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(final MouseEvent e) {
				if (table.isEnabled() && table.getModel() instanceof final FoldersRightsTableModel model) {
					model.toggleColumn(table.convertColumnIndexToModel(table.columnAtPoint(e.getPoint())));
				}
			}
		});

		final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.add(apply);
		buttons.add(close);
		final JPanel bottom = new JPanel(new BorderLayout(0, 8));
		bottom.add(notes, BorderLayout.CENTER);
		bottom.add(buttons, BorderLayout.SOUTH);
		final JPanel content = new JPanel(new BorderLayout(0, 8));
		content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		content.add(title, BorderLayout.NORTH);
		content.add(new JScrollPane(table), BorderLayout.CENTER);
		content.add(bottom, BorderLayout.SOUTH);
		frame.setContentPane(content);

		final Runnable[] load = new Runnable[1];
		final boolean[] warned = new boolean[1];
		// moteur neuf à chaque appel : il accumule les dossiers résolus
		load[0] = () -> Gui.runAsync(frame.getContentPane(), () -> {
			final RightEngine engine = new RightEngine(pref);
			engine.login();
			final List<String> errors = new ArrayList<>();
			final Map<String, FolderRights> rights = engine.folderRights(folders, errors);
			if (rights.isEmpty()) {
				throw new UserException(String.join("\n", errors));
			}
			return new Loaded(rights, errors);
		}, loaded -> {
			final List<String> paths = List.copyOf(loaded.rights().keySet());
			final List<FolderRights> rights = List.copyOf(loaded.rights().values());
			final RightsTableModel model = model(paths, rights);
			title.setText(paths.size() == 1 ? Messages.get("gui.rights.folder", rights.getFirst().folderName())
					: Messages.get("gui.rights.folders", rights.size()));
			table.setModel(model);
			if (paths.size() > 1) {
				table.getColumnModel().getColumn(0).setPreferredWidth(260);
			}
			apply.setEnabled(model.hasPendingChanges());
			model.addTableModelListener(e -> apply.setEnabled(model.hasPendingChanges()));
			// dossiers écartés signalés au premier chargement seulement
			final List<String> ignored = new ArrayList<>(skipped);
			skipped.clear();
			if (!warned[0]) {
				ignored.addAll(loaded.errors());
				warned[0] = true;
			}
			if (!ignored.isEmpty()) {
				JOptionPane.showMessageDialog(frame, Messages.get("gui.rights.skipped", String.join("\n", ignored)), Gui.TITLE,
						JOptionPane.WARNING_MESSAGE);
			}
		}, frame::dispose);
		apply.addActionListener(e -> {
			final RightsTableModel model = (RightsTableModel) table.getModel();
			final var wanted = model.wanted(); // lu sur l'EDT
			guard.begin(); // l'écriture ne doit pas être interrompue par la fermeture
			Gui.runAsync(frame.getContentPane(), () -> {
				final RightEngine engine = new RightEngine(pref);
				engine.login();
				return engine.setFolderRights(wanted);
			}, messages -> {
				guard.end();
				showReport(frame, messages);
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
		if (single) {
			frame.setSize(520, 380);
		} else {
			frame.setSize(Math.min(1000, Math.max(680, 420 + 110 * pref.getGroups().size())), Math.min(660, 260 + 26 * folders.size()));
		}
		frame.setLocationRelativeTo(null);
		return frame;
	}

	/** Droits chargés et erreurs des dossiers écartés. */
	private record Loaded(Map<String, FolderRights> rights, List<String> errors) {
	}

	static RightsTableModel model(final List<String> folders, final List<FolderRights> rights) {
		return folders.size() == 1 ? new FolderRightsTableModel(folders.getFirst(), rights.getFirst())
				: new FoldersRightsTableModel(folders, rights);
	}

	/** Compte rendu des modifications, défilant s'il est long. */
	private static void showReport(final JFrame frame, final List<String> messages) {
		if (messages.isEmpty()) {
			return;
		}
		final String text = String.join("\n", messages);
		final Object body;
		if (messages.size() > MAX_MESSAGE_LINES) {
			final JTextArea area = new JTextArea(text, MAX_MESSAGE_LINES, 60);
			area.setEditable(false);
			body = new JScrollPane(area);
		} else {
			body = text;
		}
		JOptionPane.showMessageDialog(frame, body, Gui.TITLE, JOptionPane.INFORMATION_MESSAGE);
	}

	private static Color warning() {
		final Color warning = UIManager.getColor("Component.warning.focusedBorderColor");
		return warning != null ? warning : FALLBACK;
	}

	private static boolean gap(final JTable table, final int row, final int column) {
		return table.getModel() instanceof final RightsTableModel model
				&& model.hasGap(table.convertRowIndexToModel(row), table.convertColumnIndexToModel(column));
	}

	private static String toolTip(final JTable table, final int row, final int column) {
		return table.getModel() instanceof final RightsTableModel model
				? model.toolTip(table.convertRowIndexToModel(row), table.convertColumnIndexToModel(column))
				: null;
	}

	/** Écart Photos/fichier : texte en couleur d'avertissement. */
	private static final class GapRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(final JTable table, final Object value, final boolean selected,
				final boolean focus, final int row, final int column) {
			final Component c = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
			if (!selected) {
				c.setForeground(gap(table, row, column) ? warning() : table.getForeground());
			}
			setToolTipText(toolTip(table, row, column));
			return c;
		}
	}

	/** Case à cocher standard, fond teinté en cas d'écart Photos/fichier. */
	private static final class GapCheckRenderer implements TableCellRenderer {
		private final TableCellRenderer delegate;

		GapCheckRenderer(final TableCellRenderer delegate) {
			this.delegate = delegate;
		}

		@Override
		public Component getTableCellRendererComponent(final JTable table, final Object value, final boolean selected,
				final boolean focus, final int row, final int column) {
			final Component c = delegate.getTableCellRendererComponent(table, value, selected, focus, row, column);
			// composant réutilisé d'une cellule à l'autre : fond toujours réaffecté
			if (!selected) {
				c.setBackground(gap(table, row, column) ? blend(table.getBackground(), warning()) : table.getBackground());
			}
			if (c instanceof final JComponent component) {
				component.setToolTipText(toolTip(table, row, column));
			}
			return c;
		}

		// teinte légère : un quart de la couleur d'avertissement
		private static Color blend(final Color base, final Color tint) {
			return new Color((base.getRed() * 3 + tint.getRed()) / 4, (base.getGreen() * 3 + tint.getGreen()) / 4,
					(base.getBlue() * 3 + tint.getBlue()) / 4);
		}
	}
}
