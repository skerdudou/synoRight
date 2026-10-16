package sk.synoAdmin.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.WindowConstants;

import sk.synoAdmin.engine.RightEngine;
import sk.synoAdmin.engine.SyncPlan;
import sk.synoAdmin.prefs.Preferences;

/**
 * Panneau de synchronisation : aperçu des écarts entre fichiers et Synology Photos, puis application dans un sens ou l'autre.
 */
final class SyncPanel extends JPanel {

	/** Résultat d'une analyse : le moteur (qui a lu l'état) et les deux plans. */
	private record Analysis(RightEngine engine, SyncPlan push, SyncPlan pull) {
	}

	/** Exécuteur de tâches longues (hors EDT, résultat traité sur l'EDT). */
	interface TaskRunner {
		<T> void run(Supplier<T> task, Consumer<T> onSuccess);
	}

	private final Supplier<Preferences> prefs;
	private final TaskRunner runner;
	private final JButton analyseButton = new JButton("Analyser");
	private final JButton pushButton = new JButton("Fichiers → Photos");
	private final JButton pullButton = new JButton("Photos → fichiers");
	private final JLabel summary = new JLabel("Cliquez sur Analyser pour comparer les fichiers et Synology Photos.");
	private final JLabel warnings = new JLabel();
	private final JTable table = new JTable();
	private Analysis analysis;

	/**
	 * @param prefs fournisseur de la configuration, relu à chaque analyse
	 * @param highlighted sens mis en avant (peut être null)
	 */
	SyncPanel(final Supplier<Preferences> prefs, final SyncPlan.Direction highlighted) {
		this(prefs, highlighted, null);
	}

	/**
	 * @param runner exécute les tâches longues du panneau (null : Gui.runAsync sur le panneau) ; la fenêtre de
	 *        configuration les sérialise avec les siennes (pas de synchronisation pendant un enregistrement)
	 */
	SyncPanel(final Supplier<Preferences> prefs, final SyncPlan.Direction highlighted, final TaskRunner runner) {
		super(new BorderLayout(0, 8));
		this.prefs = prefs;
		this.runner = runner;
		setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

		pushButton.setEnabled(false);
		pullButton.setEnabled(false);
		analyseButton.addActionListener(e -> analyse());
		pushButton.addActionListener(e -> applyPush());
		pullButton.addActionListener(e -> applyPull());

		final JPanel top = new JPanel(new BorderLayout(8, 0));
		top.add(summary, BorderLayout.CENTER);
		top.add(analyseButton, BorderLayout.EAST);

		final JLabel help = new JLabel("Fichiers → Photos retire aussi les droits hérités du dossier parent par les nouveaux dossiers.");
		final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.add(pushButton);
		buttons.add(pullButton);
		final JPanel notes = new JPanel(new BorderLayout(0, 4));
		notes.add(warnings, BorderLayout.NORTH);
		notes.add(help, BorderLayout.SOUTH);
		final JPanel bottom = new JPanel(new BorderLayout(8, 0));
		bottom.add(notes, BorderLayout.CENTER);
		bottom.add(buttons, BorderLayout.EAST);

		add(top, BorderLayout.NORTH);
		add(new JScrollPane(table), BorderLayout.CENTER);
		add(bottom, BorderLayout.SOUTH);
		defaultButton = highlighted == null ? null : highlighted == SyncPlan.Direction.PUSH ? pushButton : pullButton;
	}

	private final JButton defaultButton;

	/** Bouton du sens mis en avant, à poser comme bouton par défaut de la fenêtre (null si aucun). */
	JButton defaultButton() {
		return defaultButton;
	}

	/** Lance l'analyse (lecture des fichiers et de Synology Photos) hors de l'EDT ; à appeler depuis l'EDT. */
	void analyse() {
		// moteur neuf à chaque analyse : il accumule les dossiers et les avertissements
		run(() -> {
			final RightEngine engine = new RightEngine(prefs.get());
			engine.getFullState();
			return new Analysis(engine, engine.planPush(), engine.planPull());
		}, this::show);
	}

	private <T> void run(final Supplier<T> task, final Consumer<T> onSuccess) {
		if (runner == null) {
			Gui.runAsync(this, task, onSuccess);
		} else {
			runner.run(task, onSuccess);
		}
	}

	private void show(final Analysis result) {
		analysis = result;
		final SyncPlan push = result.push();
		table.setModel(new SyncTableModel(push));
		if (push.isEmpty() && result.pull().isEmpty()) {
			summary.setText("Fichiers et Synology Photos sont synchronisés (" + push.folderCount() + " dossiers).");
		} else {
			summary.setText(push.changeCount() + " écart(s) sur " + push.folderCount() + " dossiers.");
		}
		final List<String> lines = new ArrayList<>();
		if (!push.unindexedFolders().isEmpty()) {
			lines.add("Dossiers pas encore indexés par Synology Photos (ignorés) : " + String.join(", ", push.unindexedFolders()));
		}
		if (!push.unknownFolders().isEmpty()) {
			lines.add("Dossiers connus de Synology Photos mais absents du partage (ignorés) : " + String.join(", ", push.unknownFolders()));
		}
		warnings.setText(lines.isEmpty() ? "" : "<html>" + String.join("<br>", lines.stream().map(SyncPanel::escape).toList()) + "</html>");
		pushButton.setEnabled(!push.isEmpty());
		pullButton.setEnabled(!result.pull().isEmpty());
	}

	private static String escape(final String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private void applyPush() {
		final Analysis current = analysis;
		if (current != null && Gui.confirm(this, "Appliquer " + current.push().changeCount() + " modification(s) dans Synology Photos ?")) {
			run(() -> current.engine().apply(current.push()), n -> {
				JOptionPane.showMessageDialog(this, "Droits Synology Photos mis à jour.", Gui.TITLE, JOptionPane.INFORMATION_MESSAGE);
				analyse();
			});
		}
	}

	private void applyPull() {
		final Analysis current = analysis;
		if (current != null && Gui.confirm(this, "Appliquer " + current.pull().changeCount() + " modification(s) dans les fichiers de droits ?")) {
			run(() -> current.engine().apply(current.pull()), n -> {
				JOptionPane.showMessageDialog(this, n + " fichier(s) de droits mis à jour.", Gui.TITLE, JOptionPane.INFORMATION_MESSAGE);
				analyse();
			});
		}
	}

	/** Construit la fenêtre autonome (sur l'EDT, sans accès réseau) ; l'analyse démarre à l'ouverture. */
	static JFrame createWindow(final Preferences pref, final SyncPlan.Direction direction) {
		final JFrame frame = new JFrame("Synchronisation — " + Gui.TITLE);
		frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		final SyncPanel panel = new SyncPanel(() -> pref, direction);
		frame.setContentPane(panel);
		if (panel.defaultButton() != null) {
			frame.getRootPane().setDefaultButton(panel.defaultButton());
		}
		frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowOpened(final WindowEvent e) {
				panel.analyse();
			}
		});
		frame.setSize(760, 480);
		frame.setLocationRelativeTo(null);
		return frame;
	}
}
