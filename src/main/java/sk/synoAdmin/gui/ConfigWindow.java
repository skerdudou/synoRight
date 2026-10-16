package sk.synoAdmin.gui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListSelectionModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

import sk.synoAdmin.UserException;
import sk.synoAdmin.cli.SynoRightCommand;
import sk.synoAdmin.engine.Group;
import sk.synoAdmin.engine.GroupChecks;
import sk.synoAdmin.engine.SyncPlan;
import sk.synoAdmin.menu.MenuEntry;
import sk.synoAdmin.menu.MenuService;
import sk.synoAdmin.menu.MenuTarget;
import sk.synoAdmin.menu.ModernMenuInstaller;
import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.prefs.Session;
import sk.synoAdmin.synology.ConnectionCheck;
import sk.synoAdmin.synology.SynoGroup;

/**
 * Fenêtre de configuration (lanceur synoRightGui.exe) : connexion, dossier photos, groupes, menu de l'Explorateur
 * et synchronisation. Équivalent graphique de 'synoRight init', 'groups' et 'menu install'.
 * Construite sur l'EDT sans accès réseau : le test de connexion et l'état du menu sont lancés à l'ouverture.
 */
final class ConfigWindow {

	private static final String[] SECTIONS = { "Connexion", "Dossier photos", "Groupes", "Menu Explorateur", "Synchronisation" };
	private static final int CONNECTION = 0;
	private static final int FOLDER = 1;
	private static final int GROUPS = 2;
	private static final int SYNC = 4;

	/** Paramètres d'un test de connexion, relevés sur l'EDT. */
	private record ConnectionParams(String url, String user, String password, boolean passwordFromConfig, boolean sameAccount,
			String fingerprint) {
	}

	/** Connexion réussie et groupes du NAS (triés sans casse). */
	private record Connected(ConnectionCheck.Result result, List<SynoGroup> groups) {
	}

	/** Résultat du test automatique : connexion, ou erreur affichée dans la section (pas de dialogue). */
	private record AutoTest(Connected connected, RuntimeException error) {
	}

	private record MenuState(ModernMenuInstaller.Availability availability, MenuService.Status status) {
	}

	private record Saved(Preferences pref, String menuError) {
	}

	private record Installed(String message, MenuService.Status status) {
	}

	/** Ligne de la section Groupes : groupe du NAS (id) coché ou non, et son nom court (nom des fichiers). */
	private record GroupChoice(JCheckBox box, JTextField shortName, String id) {
	}

	private final Path configDir;
	private final boolean verbose;
	private final JFrame frame = new JFrame("Configuration — " + Gui.TITLE);
	private final boolean[] enabledSections = new boolean[SECTIONS.length];
	private final JList<String> sectionList = new JList<>(SECTIONS);
	private final CardLayout cards = new CardLayout();
	private final JPanel cardPanel = new JPanel(cards);
	private final Deque<Runnable> pending = new ArrayDeque<>();
	private boolean busy;

	private ConfigForm form;
	/** Configuration enregistrée (null tant qu'il n'y en a pas) ; lue hors de l'EDT par la synchronisation. */
	private volatile Preferences saved;
	/** Groupes du NAS, null tant qu'aucune connexion n'a réussi. */
	private List<SynoGroup> nasGroups;

	// Connexion
	private final JTextField urlField = new JTextField(30);
	private final JTextField userField = new JTextField(20);
	private final JPasswordField passwordField = new JPasswordField(20);
	private final JLabel passwordHint = new JLabel(" ");
	private final JCheckBox storePasswordBox = new JCheckBox("Mémoriser le mot de passe");
	private final JLabel connectionStatus = new JLabel(" ");
	private String connectionMessage = " ";
	/** Configuration existante illisible : signalée en tête de la section Connexion. */
	private String unreadableConfig;

	// Dossier photos
	private final JTextField folderField = new JTextField(40);
	private final JLabel folderStatus = new JLabel(" ");
	private final Timer folderTimer = new Timer(400, e -> checkFolder());

	// Groupes
	private final JLabel groupWarnings = new JLabel();
	private final JLabel groupHint = new JLabel(" ");
	private final JPanel groupBoxes = new JPanel();
	private final List<GroupChoice> groupChoices = new ArrayList<>();

	// Menu Explorateur
	private final Map<MenuEntry, JCheckBox> modernEntryBoxes = new EnumMap<>(MenuEntry.class);
	private final Map<MenuEntry, JCheckBox> classicEntryBoxes = new EnumMap<>(MenuEntry.class);
	private final Map<MenuTarget, JRadioButton> targetButtons = new EnumMap<>(MenuTarget.class);
	private final JLabel menuAvailability = new JLabel(" ");
	private final JLabel menuStatus = new JLabel("Installé : vérification…");
	private ModernMenuInstaller.Availability availability;

	private ConfigWindow(final Path configDir) {
		this.configDir = configDir;
		final Preferences existing = loadExisting();
		verbose = existing != null && existing.isVerbose();
		saved = existing;
		form = ConfigForm.from(existing);
		buildFrame();
		updateSections();
		refreshConnectionStatus();
		refreshPasswordHint();
		rebuildGroups();
		folderTimer.setRepeats(false);
		checkFolder();
	}

	/** Construit la fenêtre (sur l'EDT, sans accès réseau). */
	static JFrame create(final Path configDir) {
		return new ConfigWindow(configDir).frame;
	}

	private Preferences loadExisting() {
		if (!Preferences.exists(configDir)) {
			return null;
		}
		try {
			return Preferences.fromFile(configDir);
		} catch (final UserException e) {
			unreadableConfig = "Configuration existante illisible (" + e.getMessage() + ") : elle sera remplacée.";
			return null;
		}
	}

	// ---------------------------------------------------------------- fenêtre

	private void buildFrame() {
		frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowOpened(final WindowEvent e) {
				startup();
			}

			@Override
			public void windowClosing(final WindowEvent e) {
				close();
			}
		});

		cardPanel.add(connectionSection(), SECTIONS[CONNECTION]);
		cardPanel.add(folderSection(), SECTIONS[FOLDER]);
		cardPanel.add(groupsSection(), SECTIONS[GROUPS]);
		cardPanel.add(menuSection(), SECTIONS[3]);
		// tâches de la synchronisation dans la file de la fenêtre : jamais pendant un enregistrement ou une installation
		cardPanel.add(new SyncPanel(() -> saved, SyncPlan.Direction.PUSH, this::queue), SECTIONS[SYNC]);

		// sections indisponibles : grisées et non sélectionnables
		sectionList.setSelectionModel(new DefaultListSelectionModel() {
			@Override
			public void setSelectionInterval(final int anchor, final int lead) {
				if (lead >= 0 && lead < enabledSections.length && enabledSections[lead]) {
					super.setSelectionInterval(lead, lead);
				}
			}

			@Override
			public void addSelectionInterval(final int anchor, final int lead) {
				setSelectionInterval(anchor, lead);
			}
		});
		sectionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		sectionList.setCellRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(final JList<?> list, final Object value, final int index,
					final boolean isSelected, final boolean cellHasFocus) {
				final Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				c.setEnabled(enabledSections[index]);
				setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
				return c;
			}
		});
		sectionList.addListSelectionListener(e -> {
			final int index = sectionList.getSelectedIndex();
			if (index >= 0) {
				cards.show(cardPanel, SECTIONS[index]);
			}
		});
		enabledSections[CONNECTION] = true;
		sectionList.setSelectedIndex(CONNECTION);
		final JScrollPane sectionScroll = new JScrollPane(sectionList);
		sectionScroll.setPreferredSize(new java.awt.Dimension(180, 0));

		final JPanel content = new JPanel(new BorderLayout(8, 8));
		content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		content.add(sectionScroll, BorderLayout.WEST);
		content.add(cardPanel, BorderLayout.CENTER);
		content.add(bottomBar(), BorderLayout.SOUTH);
		frame.setContentPane(content);
		frame.setSize(900, 600);
		frame.setLocationRelativeTo(null);
	}

	private JPanel bottomBar() {
		final JButton save = new JButton("Enregistrer");
		save.addActionListener(e -> save(true, null));
		final JButton close = new JButton("Fermer");
		close.addActionListener(e -> close());
		frame.getRootPane().setDefaultButton(save);

		final JButton openDir = new JButton("Ouvrir le dossier de configuration");
		openDir.putClientProperty("JButton.buttonType", "borderless");
		openDir.addActionListener(e -> openConfigDir());
		final JLabel version = new JLabel(new SynoRightCommand.VersionProvider().getVersion()[0]);
		version.setEnabled(false);

		final JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		left.add(openDir);
		left.add(version);
		final JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		right.add(save);
		right.add(close);
		final JPanel bar = new JPanel(new BorderLayout());
		bar.add(left, BorderLayout.WEST);
		bar.add(right, BorderLayout.EAST);
		return bar;
	}

	/** À l'ouverture : test de connexion automatique (configuration existante) et état du menu, en arrière-plan. */
	private void startup() {
		if (form.isExisting()) {
			if (form.hasStoredPassword()) {
				autoTest();
			} else {
				connectionMessage = "Mot de passe non mémorisé : cliquer sur « Tester la connexion » pour charger les groupes du NAS.";
				refreshConnectionStatus();
			}
		}
		loadMenuState();
	}

	private void close() {
		if (busy) {
			// fermer tuerait l'opération en cours (écriture de la configuration, installation du menu…)
			JOptionPane.showMessageDialog(frame, "Opération en cours, veuillez patienter.", Gui.TITLE, JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		if (form.isDirty() && !Gui.confirm(frame, "Quitter sans enregistrer les modifications ?")) {
			return;
		}
		pending.clear();
		folderTimer.stop();
		frame.dispose();
	}

	/** Sections 2 à 4 : après une connexion réussie (ou configuration existante) ; synchronisation : configuration enregistrée. */
	private void updateSections() {
		final boolean ready = form.isExisting() || form.hasConnected();
		for (int i = FOLDER; i < SYNC; i++) {
			enabledSections[i] = ready;
		}
		enabledSections[SYNC] = saved != null;
		sectionList.repaint();
	}

	private void selectSection(final int index) {
		if (enabledSections[index]) {
			sectionList.setSelectedIndex(index);
		}
	}

	private void openConfigDir() {
		if (!Files.isDirectory(configDir)) {
			JOptionPane.showMessageDialog(frame, "Le dossier de configuration sera créé à l'enregistrement :\n" + configDir,
					Gui.TITLE, JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		async(() -> {
			try {
				Desktop.getDesktop().open(configDir.toFile());
			} catch (final IOException | UnsupportedOperationException e) {
				throw new UserException("Impossible d'ouvrir le dossier " + configDir + " : " + e.getMessage(), e);
			}
			return null;
		}, r -> {
			// rien
		}, () -> {
			// erreur déjà affichée
		});
	}

	/**
	 * Tâche longue hors de l'EDT ; les tâches de la fenêtre (synchronisation comprise) s'exécutent l'une après
	 * l'autre : pas de synchronisation pendant un enregistrement, et un résultat est traité avant la tâche suivante.
	 * Le contenu de la fenêtre est désactivé pendant chaque tâche et la fermeture refusée tant que la file n'est pas vide.
	 */
	private <T> void async(final Supplier<T> task, final Consumer<T> onSuccess, final Runnable onFailure) {
		final boolean[] finished = { false };
		final Runnable next = () -> {
			if (!finished[0]) {
				finished[0] = true;
				runNext();
			}
		};
		final Runnable start = () -> Gui.runAsync(frame.getContentPane(), task, result -> {
			try {
				onSuccess.accept(result);
			} finally {
				next.run();
			}
		}, () -> {
			try {
				onFailure.run();
			} finally {
				next.run();
			}
		});
		if (busy) {
			pending.add(start);
		} else {
			busy = true;
			start.run();
		}
	}

	/** Tâche de la synchronisation, mise dans la file de la fenêtre (erreur affichée par Gui.runAsync). */
	private <T> void queue(final Supplier<T> task, final Consumer<T> onSuccess, final Runnable onFailure) {
		async(task, onSuccess, onFailure);
	}

	private void runNext() {
		final Runnable next = pending.poll();
		if (next == null) {
			busy = false;
		} else {
			next.run();
		}
	}

	// ---------------------------------------------------------------- Connexion

	private JPanel connectionSection() {
		urlField.setText(form.url);
		userField.setText(form.user);
		storePasswordBox.setSelected(form.storePassword);
		final JButton testButton = new JButton("Tester la connexion");
		testButton.addActionListener(e -> manualTest());

		onChange(urlField, () -> {
			form.url = urlField.getText().trim();
			refreshConnectionStatus();
		});
		onChange(userField, () -> {
			form.user = userField.getText().trim();
			refreshConnectionStatus();
		});
		onChange(passwordField, () -> {
			form.setPasswordInput(new String(passwordField.getPassword()));
			refreshConnectionStatus();
		});
		storePasswordBox.addActionListener(e -> form.storePassword = storePasswordBox.isSelected());

		final JPanel grid = new JPanel(new GridBagLayout());
		if (unreadableConfig != null) {
			addRow(grid, 0, null, new JLabel(html(unreadableConfig)));
		}
		addRow(grid, 1, "URL de DSM :", urlField);
		addRow(grid, 2, "Compte :", userField);
		addRow(grid, 3, "Mot de passe :", passwordField);
		addRow(grid, 4, null, passwordHint);
		addRow(grid, 5, null, storePasswordBox);
		addRow(grid, 6, null, leftFlow(testButton));
		addRow(grid, 7, null, connectionStatus);
		return section("Connexion au NAS", "URL de DSM (ex : https://nas.example.com:5001) et compte administrateur de Synology Photos.", grid);
	}

	private void refreshConnectionStatus() {
		connectionStatus.setText(form.connectionVerified() ? html(connectionMessage) : "Tester la connexion pour valider ces paramètres.");
	}

	private void refreshPasswordHint() {
		passwordHint.setText(form.hasStoredPassword() ? "Laisser vide pour conserver le mot de passe actuel."
				: form.isExisting() ? "Mot de passe non mémorisé dans la configuration." : " ");
	}

	private ConnectionParams connectionParams() {
		return new ConnectionParams(ConnectionCheck.normalizeUrl(form.url), form.user, form.password, form.passwordFromConfig(),
				form.sameAccount(), form.fingerprint);
	}

	/** Connexion (dialogues OTP, certificat ou mot de passe via SwingInteraction) puis lecture des groupes ; hors EDT. */
	private Connected connect(final ConnectionParams p) {
		final ConnectionCheck.Result result = ConnectionCheck.connect(p.url(), p.user(), p.password(), p.passwordFromConfig(),
				Session.load(configDir), p.sameAccount(), p.fingerprint());
		final List<SynoGroup> groups = result.client().getGroups().stream()
				.sorted(Comparator.comparing(SynoGroup::name, String.CASE_INSENSITIVE_ORDER))
				.toList();
		return new Connected(result, groups);
	}

	private void manualTest() {
		if (form.url.isBlank() || form.user.isBlank()) {
			JOptionPane.showMessageDialog(frame, "Saisir l'URL de DSM et le compte.", Gui.TITLE, JOptionPane.WARNING_MESSAGE);
			return;
		}
		final ConnectionParams params = connectionParams();
		connectionStatus.setText("Test de la connexion…");
		async(() -> connect(params), this::connected, () -> {
			connectionMessage = "Échec de la connexion.";
			refreshConnectionStatus();
		});
	}

	/** Test automatique d'une configuration existante : un échec est signalé dans la section, sans dialogue bloquant. */
	private void autoTest() {
		final ConnectionParams params = connectionParams();
		connectionStatus.setText("Test de la connexion…");
		async(() -> {
			try {
				return new AutoTest(connect(params), null);
			} catch (final RuntimeException e) {
				return new AutoTest(null, e);
			}
		}, outcome -> {
			if (outcome.connected() != null) {
				connected(outcome.connected());
			} else {
				final RuntimeException e = outcome.error();
				connectionMessage = "Échec de la connexion : " + (e.getMessage() != null ? e.getMessage() : e.toString());
				refreshConnectionStatus();
			}
		}, () -> {
			// ne se produit pas : l'erreur est rendue dans le résultat
		});
	}

	private void connected(final Connected connection) {
		form.connected(connection.result());
		nasGroups = connection.groups();
		connectionMessage = "Connexion OK.";
		refreshConnectionStatus();
		rebuildGroups();
		updateSections();
	}

	// ---------------------------------------------------------------- Dossier photos

	private JPanel folderSection() {
		folderField.setText(form.folder);
		onChange(folderField, () -> {
			form.folder = folderField.getText().trim();
			folderStatus.setText(" ");
			folderTimer.restart();
		});
		final JButton browse = new JButton("Parcourir…");
		browse.addActionListener(e -> browseFolder());

		final JPanel line = new JPanel(new BorderLayout(8, 0));
		line.add(folderField, BorderLayout.CENTER);
		line.add(browse, BorderLayout.EAST);
		final JPanel grid = new JPanel(new GridBagLayout());
		addRow(grid, 0, "Dossier :", line);
		addRow(grid, 1, null, folderStatus);
		return section("Dossier photos", "Dossier des photos sur le NAS, vu depuis ce poste (ex : \\\\NAS\\photo).", grid);
	}

	private void browseFolder() {
		final JFileChooser chooser = new JFileChooser();
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		chooser.setDialogTitle("Dossier des photos");
		if (!form.folder.isBlank()) {
			chooser.setCurrentDirectory(new File(form.folder));
		}
		if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
			folderField.setText(chooser.getSelectedFile().getAbsolutePath());
		}
	}

	/** Vérifie le dossier hors de l'EDT (un partage réseau injoignable peut mettre du temps à répondre). */
	private void checkFolder() {
		final String folder = form.folder;
		new SwingWorker<Boolean, Void>() {
			@Override
			protected Boolean doInBackground() {
				return ConfigForm.isAccessibleFolder(folder);
			}

			@Override
			protected void done() {
				if (!folder.equals(form.folder)) {
					return; // saisie modifiée entre-temps : une autre vérification suit
				}
				boolean accessible;
				try {
					accessible = get();
				} catch (final Exception e) {
					accessible = false;
				}
				folderStatus.setText(folder.isBlank() ? "Indiquer le dossier des photos."
						: accessible ? "Dossier accessible." : "Dossier introuvable ou inaccessible.");
			}
		}.execute();
	}

	// ---------------------------------------------------------------- Groupes

	private JPanel groupsSection() {
		groupBoxes.setLayout(new GridBagLayout());
		final JPanel top = new JPanel(new BorderLayout(0, 4));
		top.add(groupWarnings, BorderLayout.NORTH);
		top.add(groupHint, BorderLayout.SOUTH);
		final JPanel content = new JPanel(new BorderLayout(0, 8));
		content.add(top, BorderLayout.NORTH);
		final JPanel boxesTop = new JPanel(new BorderLayout());
		boxesTop.add(groupBoxes, BorderLayout.NORTH);
		content.add(new JScrollPane(boxesTop), BorderLayout.CENTER);
		final JPanel section = section("Groupes", "Groupes du NAS dont les droits sont gérés. Le nom court (par défaut le nom du groupe)"
				+ " donne le nom des fichiers @<nom court>.synoRight et des entrées du menu.", null);
		section.add(content, BorderLayout.CENTER);
		return section;
	}

	/** Groupes du NAS (cochés si gérés) ; avant la connexion, groupes configurés non modifiables. */
	private void rebuildGroups() {
		groupBoxes.removeAll();
		groupChoices.clear();
		if (nasGroups == null) {
			groupHint.setText("Tester la connexion pour modifier les groupes.");
			int row = 0;
			for (final Group group : form.groups) {
				final JCheckBox box = new JCheckBox("Groupe d'id " + group.getId(), true);
				box.setEnabled(false);
				final JTextField shortName = new JTextField(group.getName(), 16);
				shortName.setEnabled(false);
				addGroupRow(row++, box, shortName);
			}
			groupWarnings.setText("");
		} else {
			groupHint.setText(nasGroups.isEmpty() ? "Aucun groupe local trouvé sur le NAS : en créer un dans DSM." : "Groupes à gérer :");
			for (final SynoGroup nasGroup : nasGroups) {
				final String id = String.valueOf(nasGroup.gid());
				final Optional<Group> configured = form.groups.stream().filter(g -> g.getId().equals(id)).findAny();
				// groupe déjà configuré : son nom court est conservé (il donne le nom des fichiers de droits)
				addGroupChoice(id, nasGroup.name(), configured.map(Group::getName).orElse(nasGroup.name()), configured.isPresent());
			}
			for (final Group group : form.groups) {
				if (nasGroups.stream().noneMatch(g -> String.valueOf(g.gid()).equals(group.getId()))) {
					addGroupChoice(group.getId(), "Groupe d'id " + group.getId() + " (absent du NAS)", group.getName(), true);
				}
			}
			refreshGroupWarnings();
		}
		groupBoxes.revalidate();
		groupBoxes.repaint();
	}

	private void addGroupChoice(final String id, final String label, final String shortNameValue, final boolean selected) {
		final JCheckBox box = new JCheckBox(label, selected);
		final JTextField shortName = new JTextField(shortNameValue, 16);
		shortName.setEnabled(selected);
		box.addActionListener(e -> {
			shortName.setEnabled(box.isSelected());
			updateFormGroups();
		});
		onChange(shortName, this::updateFormGroups);
		groupChoices.add(new GroupChoice(box, shortName, id));
		addGroupRow(groupChoices.size() - 1, box, shortName);
	}

	private void addGroupRow(final int row, final JCheckBox box, final JTextField shortName) {
		final GridBagConstraints c = new GridBagConstraints();
		c.gridy = row;
		c.insets = new Insets(2, 0, 2, 12);
		c.anchor = GridBagConstraints.LINE_START;
		c.gridx = 0;
		groupBoxes.add(box, c);
		c.gridx = 1;
		groupBoxes.add(new JLabel("Nom court :"), c);
		c.gridx = 2;
		c.weightx = 1;
		groupBoxes.add(shortName, c);
	}

	/** Groupes gérés d'après les lignes cochées et leurs noms courts. */
	private void updateFormGroups() {
		form.groups = groupChoices.stream()
				.filter(c -> c.box().isSelected())
				.map(c -> new Group(c.id(), c.shortName().getText().trim()))
				.toList();
		refreshGroupWarnings();
	}

	private void refreshGroupWarnings() {
		final List<String> warnings = new ArrayList<>(GroupChecks.warnings(nasGroups, form.groups));
		for (final Group group : form.groups) {
			final String reason = GroupChecks.invalidShortName(group.getName(), form.groups, group.getId());
			if (reason != null) {
				warnings.add("Nom court « " + group.getName() + " » invalide : " + reason + ".");
			}
		}
		warnings.addAll(form.renamedGroupNotes());
		groupWarnings.setText(warnings.isEmpty() ? ""
				: "<html>" + warnings.stream().map(ConfigWindow::escape).collect(Collectors.joining("<br>")) + "</html>");
	}

	// ---------------------------------------------------------------- Menu Explorateur

	private JPanel menuSection() {
		// une ligne par entrée, une colonne par menu : les deux menus peuvent proposer des entrées différentes
		final JPanel entries = new JPanel(new GridBagLayout());
		entries.setBorder(BorderFactory.createTitledBorder("Entrées du menu"));
		final GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(2, 0, 2, 16);
		c.anchor = GridBagConstraints.LINE_START;
		c.gridy = 0;
		c.gridx = 1;
		entries.add(new JLabel("Windows 11"), c);
		c.gridx = 2;
		entries.add(new JLabel("Classique"), c);
		for (final MenuEntry entry : MenuEntry.values()) {
			c.gridy++;
			c.gridx = 0;
			entries.add(new JLabel(entry.label()), c);
			c.gridx = 1;
			entries.add(entryBox(entry, true), c);
			c.gridx = 2;
			entries.add(entryBox(entry, false), c);
		}

		final JPanel targets = new JPanel();
		targets.setLayout(new BoxLayout(targets, BoxLayout.Y_AXIS));
		targets.setBorder(BorderFactory.createTitledBorder("Menu à installer"));
		final ButtonGroup group = new ButtonGroup();
		addTarget(targets, group, MenuTarget.MODERN, "Windows 11 (menu principal)");
		addTarget(targets, group, MenuTarget.CLASSIC, "Menu classique (Afficher plus d'options)");
		addTarget(targets, group, MenuTarget.ALL, "Les deux");
		// disponibilité du menu Windows 11 vérifiée à l'ouverture
		targetButtons.get(MenuTarget.MODERN).setEnabled(false);
		targetButtons.get(MenuTarget.ALL).setEnabled(false);
		targets.add(menuAvailability);

		final JButton install = new JButton("Installer / Mettre à jour");
		install.addActionListener(e -> installMenu());
		final JButton uninstall = new JButton("Désinstaller");
		uninstall.addActionListener(e -> uninstallMenu());

		final JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		for (final JPanel part : List.of(entries, targets)) {
			part.setAlignmentX(Component.LEFT_ALIGNMENT);
			content.add(part);
		}
		final JPanel status = leftFlow(menuStatus);
		status.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(status);
		final JPanel buttons = leftFlow(install, uninstall);
		buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(buttons);
		return section("Menu de l'Explorateur", "Clic droit sur un dossier photo : droits, synchronisation, ouverture dans Synology Photos.", content);
	}

	/** Case d'une entrée pour le menu Windows 11 (modern) ou le menu classique. */
	private JCheckBox entryBox(final MenuEntry entry, final boolean modern) {
		final Map<MenuEntry, JCheckBox> boxes = modern ? modernEntryBoxes : classicEntryBoxes;
		final JCheckBox box = new JCheckBox("", (modern ? form.modernMenuEntries : form.classicMenuEntries).contains(entry));
		box.addActionListener(e -> {
			final List<MenuEntry> selected = Arrays.stream(MenuEntry.values()).filter(m -> boxes.get(m).isSelected()).toList();
			if (modern) {
				form.modernMenuEntries = selected;
			} else {
				form.classicMenuEntries = selected;
			}
		});
		boxes.put(entry, box);
		return box;
	}

	private void addTarget(final JPanel panel, final ButtonGroup group, final MenuTarget target, final String label) {
		final JRadioButton button = new JRadioButton(label, target == form.menuTarget);
		button.addActionListener(e -> form.menuTarget = target);
		group.add(button);
		targetButtons.put(target, button);
		panel.add(button);
	}

	private void loadMenuState() {
		async(() -> new MenuState(ModernMenuInstaller.availability(), MenuService.status()), state -> {
			availability = state.availability();
			final boolean available = availability.available();
			for (final MenuTarget target : List.of(MenuTarget.MODERN, MenuTarget.ALL)) {
				final JRadioButton button = targetButtons.get(target);
				button.setEnabled(available);
				button.setToolTipText(available ? null : availability.reason());
			}
			menuAvailability.setText(available ? " " : "Menu Windows 11 indisponible : " + availability.reason() + ".");
			if (form.menuTarget == null) {
				// choix automatique : on montre la cible effective sans la figer dans la configuration
				targetButtons.get(available ? MenuTarget.MODERN : MenuTarget.CLASSIC).setSelected(true);
			}
			showMenuStatus(state.status());
		}, () -> menuStatus.setText("Installé : état inconnu."));
	}

	private void showMenuStatus(final MenuService.Status status) {
		menuStatus.setText("Installé : " + (status.classic() && status.modern() ? "menu Windows 11 et menu classique."
				: status.modern() ? "menu Windows 11." : status.classic() ? "menu classique." : "aucun menu."));
	}

	private MenuTarget selectedTarget() {
		if (form.menuTarget != null) {
			return form.menuTarget;
		}
		return availability != null && availability.available() ? MenuTarget.MODERN : MenuTarget.CLASSIC;
	}

	/** Enregistre d'abord la configuration, puis installe le menu. */
	private void installMenu() {
		final MenuTarget target = selectedTarget();
		save(false, pref -> async(() -> {
			final String message = MenuService.install(pref, target);
			return new Installed(message, MenuService.status());
		}, result -> {
			showMenuStatus(result.status());
			JOptionPane.showMessageDialog(frame, "Configuration enregistrée.\n" + result.message(), Gui.TITLE, JOptionPane.INFORMATION_MESSAGE);
		}, this::reloadMenuStatus));
	}

	private void uninstallMenu() {
		if (!Gui.confirm(frame, "Désinstaller le menu contextuel de l'Explorateur ?")) {
			return;
		}
		async(() -> {
			MenuService.uninstall();
			return MenuService.status();
		}, status -> {
			showMenuStatus(status);
			JOptionPane.showMessageDialog(frame, "Menu contextuel désinstallé.", Gui.TITLE, JOptionPane.INFORMATION_MESSAGE);
		}, this::reloadMenuStatus);
	}

	/** Après un échec d'installation : l'état réel peut avoir changé (installation partielle). */
	private void reloadMenuStatus() {
		async(MenuService::status, this::showMenuStatus, () -> menuStatus.setText("Installé : état inconnu."));
	}

	// ---------------------------------------------------------------- Enregistrement

	/**
	 * Valide puis enregistre la configuration (session de travail du test de connexion comprise) hors de l'EDT.
	 *
	 * @param refreshMenu met à jour les menus installés (nouveaux groupes ou entrées)
	 * @param then suite après l'enregistrement (null : message « Configuration enregistrée. »)
	 */
	private void save(final boolean refreshMenu, final Consumer<Preferences> then) {
		final List<String> problems = form.validate();
		if (!problems.isEmpty()) {
			final String first = problems.get(0);
			selectSection(first.equals(ConfigForm.CONNECTION_REQUIRED) ? CONNECTION : first.equals(ConfigForm.FOLDER_INVALID) ? FOLDER : GROUPS);
			JOptionPane.showMessageDialog(frame, "Configuration incomplète :\n- " + String.join("\n- ", problems), Gui.TITLE,
					JOptionPane.WARNING_MESSAGE);
			return;
		}
		final ConfigForm current = form;
		async(() -> {
			// la session mémorisée n'est remplacée qu'à l'enregistrement
			final Session workSession = current.workSession();
			if (workSession != null) {
				Session.load(configDir).replaceWith(workSession);
			}
			final Preferences pref = current.toPreferences(configDir, verbose);
			pref.save();
			String menuError = null;
			if (refreshMenu) {
				try {
					MenuService.refresh(pref);
				} catch (final RuntimeException e) {
					menuError = e.getMessage();
				}
			}
			return new Saved(pref, menuError);
		}, result -> {
			saved = result.pref();
			form = current.afterSave(result.pref());
			passwordField.setText("");
			refreshPasswordHint();
			refreshConnectionStatus();
			updateSections();
			if (then != null) {
				then.accept(result.pref());
			} else if (result.menuError() != null) {
				JOptionPane.showMessageDialog(frame, "Configuration enregistrée.\nMenu contextuel non mis à jour : " + result.menuError(),
						Gui.TITLE, JOptionPane.WARNING_MESSAGE);
			} else {
				JOptionPane.showMessageDialog(frame, "Configuration enregistrée.", Gui.TITLE, JOptionPane.INFORMATION_MESSAGE);
			}
		}, () -> {
			// erreur déjà affichée, rien n'a changé dans le formulaire
		});
	}

	// ---------------------------------------------------------------- mise en page

	private static JPanel section(final String title, final String description, final Component content) {
		final JLabel titleLabel = new JLabel(title);
		titleLabel.putClientProperty("FlatLaf.styleClass", "h2");
		final JPanel header = new JPanel(new BorderLayout(0, 4));
		header.add(titleLabel, BorderLayout.NORTH);
		header.add(new JLabel(html(description)), BorderLayout.SOUTH);
		final JPanel panel = new JPanel(new BorderLayout(0, 12));
		panel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
		panel.add(header, BorderLayout.NORTH);
		if (content != null) {
			// contenu calé en haut
			final JPanel top = new JPanel(new BorderLayout());
			top.add(content, BorderLayout.NORTH);
			panel.add(top, BorderLayout.CENTER);
		}
		return panel;
	}

	private static void addRow(final JPanel grid, final int row, final String label, final Component field) {
		final GridBagConstraints c = new GridBagConstraints();
		c.gridy = row;
		c.insets = new Insets(4, 0, 4, 8);
		c.anchor = GridBagConstraints.LINE_START;
		if (label != null) {
			c.gridx = 0;
			grid.add(new JLabel(label), c);
		}
		c.gridx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		grid.add(field, c);
	}

	private static JPanel leftFlow(final Component... components) {
		final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		for (final Component component : components) {
			panel.add(component);
		}
		return panel;
	}

	private static void onChange(final JTextComponent field, final Runnable action) {
		field.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(final DocumentEvent e) {
				action.run();
			}

			@Override
			public void removeUpdate(final DocumentEvent e) {
				action.run();
			}

			@Override
			public void changedUpdate(final DocumentEvent e) {
				action.run();
			}
		});
	}

	private static String html(final String text) {
		return text.isBlank() ? " " : "<html>" + escape(text) + "</html>";
	}

	private static String escape(final String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
