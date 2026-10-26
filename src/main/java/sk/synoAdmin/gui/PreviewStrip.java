package sk.synoAdmin.gui;

import java.awt.BasicStroke;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import sk.synoAdmin.LogUtil;
import sk.synoAdmin.engine.RightEngine;
import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.preview.PreviewImages;
import sk.synoAdmin.preview.PreviewImages.Preview;
import sk.synoAdmin.preview.PreviewImages.Source;
import sk.synoAdmin.preview.PreviewItem;
import sk.synoAdmin.preview.PreviewLoader;
import sk.synoAdmin.preview.PreviewPicker;

/**
 * Bandeau d'aperçus des photos d'un dossier (fenêtre « Droits du dossier ») : jusqu'à {@link PreviewPicker#MAX}
 * vignettes chargées une à une en arrière-plan, sur un seul fil par fenêtre ; clic sur une vignette ⇒ aperçu agrandi.
 * Le chargement ne grise pas la fenêtre et n'empêche pas sa fermeture ; une erreur n'ouvre jamais de boîte de dialogue.
 * Méthodes publiques sur l'EDT.
 */
final class PreviewStrip extends JPanel {
	private static final long serialVersionUID = 1L;

	static final String SELECT_FOLDER = "gui.previews.selectFolder";
	static final String EMPTY = "gui.previews.empty";
	static final String UNAVAILABLE = "gui.previews.unavailable";
	static final String LOADING = "gui.previews.loading";
	static final String RETRY = "gui.previews.retry";

	private static final int THUMB = PreviewImages.THUMB_SIZE;
	private static final int TILE_GAP = 6;
	private static final String MESSAGE_CARD = "message";
	private static final String TILES_CARD = "tiles";
	private static final Color NEUTRAL = new Color(0x9AA0A6);

	/**
	 * Accès aux aperçus (fil de chargement seulement) ; une exception de pick ⇒ « aperçu indisponible ».
	 */
	interface Backend {
		List<PreviewItem> pick(String folder);

		Preview load(PreviewItem item);

		Preview loadLarge(PreviewItem item, int maxSide);
	}

	/**
	 * Affichage du bandeau (appelé sur le fil d'interface).
	 */
	interface View {
		/** Texte à la place des vignettes (clé de message). */
		void message(String key);

		/** Cases d'attente, une par élément. */
		void start(List<PreviewItem> items);

		/** Image d'une case. */
		void tile(int index, Preview preview);
	}

	/**
	 * Suivi des chargements, sans affichage : compteur de génération (un résultat d'une génération dépassée est
	 * ignoré) et cache des dossiers entièrement chargés. show, clear et dispose sur le fil d'interface.
	 */
	static final class Loading {
		private final Backend backend;
		private final Executor worker;
		private final Executor ui;
		private final View view;
		private final AtomicInteger generation = new AtomicInteger();
		// dossiers entièrement chargés seulement (un chargement abandonné n'y entre pas)
		private final Map<String, List<Preview>> cache = new ConcurrentHashMap<>();
		// aperçu agrandi demandé, servi avant la vignette suivante du bandeau ; une nouvelle demande remplace celle en
		// attente (un seul fil : la session et le moteur du NAS ne sont jamais partagés entre fils)
		private final AtomicReference<Runnable> pendingLarge = new AtomicReference<>();
		private volatile boolean disposed;
		// dossier affiché ou en cours de chargement (fil d'interface)
		private String current;
		// dossier dont le chargement vient d'échouer, à réessayer sur demande (fil d'interface)
		private String failed;

		Loading(final Backend backend, final Executor worker, final Executor ui, final View view) {
			this.backend = backend;
			this.worker = worker;
			this.ui = ui;
			this.view = view;
		}

		/** Aperçus du dossier : depuis le cache, sinon chargement (celui en cours est abandonné). */
		void show(final String folder) {
			if (disposed || folder.equals(current)) {
				return;
			}
			current = folder;
			failed = null;
			final int gen = generation.incrementAndGet();
			final List<Preview> cached = cache.get(folder);
			if (cached != null) {
				display(cached);
				return;
			}
			view.message(LOADING);
			worker.execute(() -> load(gen, folder));
		}

		/** Aucun dossier sélectionné : chargement en cours abandonné, invitation à choisir. */
		void clear() {
			if (disposed) {
				return;
			}
			current = null;
			failed = null;
			generation.incrementAndGet();
			view.message(SELECT_FOLDER);
		}

		/** Nouvel essai du dossier dont l'aperçu est indisponible (clic sur le message) ; sans effet sinon. */
		void retry() {
			if (failed != null && current == null) {
				show(failed);
			}
		}

		/** Fenêtre fermée : plus aucun affichage ni chargement. */
		void dispose() {
			disposed = true;
			generation.incrementAndGet();
		}

		/**
		 * Image agrandie chargée sur le fil de chargement, avant les vignettes du bandeau encore en attente ; remplace une
		 * demande pas encore servie ; ignorée si wanted devient faux entre-temps.
		 */
		void loadLarge(final PreviewItem item, final int maxSide, final BooleanSupplier wanted, final Consumer<Preview> onUi) {
			if (disposed) {
				return;
			}
			pendingLarge.set(() -> large(item, maxSide, wanted, onUi));
			// servie ici si le fil est libre, sinon par le chargement du bandeau entre deux vignettes
			worker.execute(this::runPendingLarge);
		}

		// fil de chargement
		private void runPendingLarge() {
			final Runnable task = pendingLarge.getAndSet(null);
			if (task != null) {
				task.run();
			}
		}

		// fil de chargement
		private void large(final PreviewItem item, final int maxSide, final BooleanSupplier wanted,
				final Consumer<Preview> onUi) {
			if (disposed || !wanted.getAsBoolean()) {
				return;
			}
			Preview preview;
			try {
				preview = backend.loadLarge(item, maxSide);
			} catch (final RuntimeException e) {
				LogUtil.debug("Large preview unavailable for " + item.file() + " : " + e);
				preview = new Preview(item, Optional.empty(), Source.ICON, e.toString());
			}
			final Preview result = preview;
			ui.execute(() -> {
				if (!disposed && wanted.getAsBoolean()) {
					onUi.accept(result);
				}
			});
		}

		private boolean isCurrent(final int gen) {
			return !disposed && generation.get() == gen;
		}

		private void display(final List<Preview> previews) {
			if (previews.isEmpty()) {
				view.message(EMPTY);
				return;
			}
			view.start(previews.stream().map(Preview::item).toList());
			for (int i = 0; i < previews.size(); i++) {
				view.tile(i, previews.get(i));
			}
		}

		// fil de chargement
		private void load(final int gen, final String folder) {
			if (!isCurrent(gen)) {
				return;
			}
			try {
				final List<PreviewItem> items = backend.pick(folder);
				if (items.isEmpty()) {
					cache.put(folder, List.of());
					onUi(gen, () -> view.message(EMPTY));
					return;
				}
				onUi(gen, () -> view.start(items));
				final List<Preview> previews = new ArrayList<>();
				for (int i = 0; i < items.size(); i++) {
					runPendingLarge();
					if (!isCurrent(gen)) {
						return; // abandonné : rien en cache
					}
					final Preview preview = backend.load(items.get(i));
					previews.add(preview);
					final int index = i;
					onUi(gen, () -> view.tile(index, preview));
				}
				cache.put(folder, List.copyOf(previews));
			} catch (final RuntimeException e) {
				LogUtil.debug("Previews unavailable for " + folder + " : " + e);
				onUi(gen, () -> {
					current = null; // nouvel essai à la prochaine demande ou par clic sur le message
					failed = folder;
					view.message(UNAVAILABLE);
				});
			}
		}

		private void onUi(final int gen, final Runnable action) {
			ui.execute(() -> {
				if (isCurrent(gen)) {
					action.run();
				}
			});
		}
	}

	/**
	 * Accès au NAS : moteur connecté à la première demande (fil de chargement seulement), session réutilisée ensuite.
	 */
	private static final class NasBackend implements Backend {
		private final Preferences pref;
		private RightEngine engine;
		private PreviewLoader loader;

		NasBackend(final Preferences pref) {
			this.pref = pref;
		}

		private RightEngine engine() {
			if (engine == null) {
				final RightEngine connected = new RightEngine(pref);
				connected.login();
				loader = new PreviewLoader(connected.client());
				engine = connected;
			}
			return engine;
		}

		@Override
		public List<PreviewItem> pick(final String folder) {
			try {
				final RightEngine re = engine();
				return PreviewPicker.pick(re.folderId(folder), Paths.get(folder), PreviewPicker.PhotoSource.of(re.client()));
			} catch (final RuntimeException e) {
				engine = null; // session peut-être expirée : reconnexion à la prochaine demande
				throw e;
			}
		}

		@Override
		public Preview load(final PreviewItem item) {
			engine();
			return loader.load(item);
		}

		@Override
		public Preview loadLarge(final PreviewItem item, final int maxSide) {
			engine();
			return loader.loadLarge(item, maxSide);
		}
	}

	private final transient ExecutorService executor;
	private final transient Loading loading;
	private final CardLayout cards = new CardLayout();
	private final JLabel message = new JLabel(" ", SwingConstants.CENTER);
	private final JPanel tiles = new JPanel(new FlowLayout(FlowLayout.LEFT, TILE_GAP, 0));
	private final JScrollPane scroll = new JScrollPane(tiles, JScrollPane.VERTICAL_SCROLLBAR_NEVER,
			JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
	// cases affichées (EDT)
	private final List<Tile> shown = new ArrayList<>();

	PreviewStrip(final Preferences pref) {
		super();
		setLayout(cards);
		executor = Executors.newSingleThreadExecutor(task -> {
			final Thread thread = new Thread(task, "previews");
			thread.setDaemon(true);
			return thread;
		});
		loading = new Loading(new NasBackend(pref), executor, SwingUtilities::invokeLater, new StripView());
		message.setEnabled(false); // texte discret (les clics restent reçus)
		message.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(final MouseEvent e) {
				if (SwingUtilities.isLeftMouseButton(e)) {
					loading.retry();
				}
			}
		});
		scroll.setBorder(BorderFactory.createEmptyBorder());
		add(message, MESSAGE_CARD);
		add(scroll, TILES_CARD);
		// hauteur fixe : une case et la barre de défilement éventuelle
		final int height = tileSize().height + new JScrollBar(JScrollBar.HORIZONTAL).getPreferredSize().height + 2;
		setPreferredSize(new Dimension(THUMB, height));
		setMinimumSize(new Dimension(0, height));
	}

	/** Aperçus du dossier (chemin local). */
	void show(final String folder) {
		loading.show(folder);
	}

	/** Aucun dossier sélectionné. */
	void clear() {
		loading.clear();
	}

	/** Fermeture de la fenêtre : arrête le fil de chargement. */
	void dispose() {
		loading.dispose();
		executor.shutdownNow();
	}

	private Dimension tileSize() {
		return new Dimension(THUMB + 8, THUMB + 4 + getFontMetrics(getFont()).getHeight());
	}

	private void open(final int index) {
		final List<Tile> current = List.copyOf(shown);
		PreviewDialog.open(SwingUtilities.getWindowAncestor(this), current.stream().map(t -> t.item).toList(),
				i -> Optional.ofNullable(current.get(i).preview), index, loading::loadLarge);
	}

	/** Affichage sur l'EDT. */
	private final class StripView implements View {
		@Override
		public void message(final String key) {
			shown.clear();
			tiles.removeAll();
			message.setText(Messages.get(key));
			// aperçu indisponible : un clic sur le message réessaie le même dossier
			final boolean retry = UNAVAILABLE.equals(key);
			message.setToolTipText(retry ? Messages.get(RETRY) : null);
			message.setCursor(Cursor.getPredefinedCursor(retry ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
			cards.show(PreviewStrip.this, MESSAGE_CARD);
		}

		@Override
		public void start(final List<PreviewItem> items) {
			shown.clear();
			tiles.removeAll();
			for (int i = 0; i < items.size(); i++) {
				final Tile tile = new Tile(i, items.get(i));
				shown.add(tile);
				tiles.add(tile);
			}
			tiles.revalidate();
			tiles.repaint();
			scroll.getHorizontalScrollBar().setValue(0);
			cards.show(PreviewStrip.this, TILES_CARD);
		}

		@Override
		public void tile(final int index, final Preview preview) {
			if (index < shown.size()) {
				shown.get(index).setPreview(preview);
			}
		}
	}

	/** Case du bandeau : attente, image centrée, ou icône neutre et nom du fichier. */
	private final class Tile extends JComponent {
		private static final long serialVersionUID = 1L;

		private final int index;
		private final transient PreviewItem item;
		private transient Preview preview;

		Tile(final int index, final PreviewItem item) {
			this.index = index;
			this.item = item;
			setPreferredSize(tileSize());
			setToolTipText(item.fileName());
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(final MouseEvent e) {
					if (SwingUtilities.isLeftMouseButton(e)) {
						open(Tile.this.index);
					}
				}
			});
		}

		void setPreview(final Preview preview) {
			this.preview = preview;
			repaint();
		}

		@Override
		protected void paintComponent(final Graphics g) {
			final Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				final Rectangle box = new Rectangle((getWidth() - THUMB) / 2, 0, THUMB, THUMB);
				if (preview == null) {
					g2.setColor(placeholder());
					g2.fillRoundRect(box.x, box.y, box.width, box.height, 10, 10);
				} else if (preview.image().isPresent()) {
					drawFitted(g2, preview.image().get(), box, false);
				} else {
					paintIcon(g2, box);
					g2.setColor(getForeground());
					final FontMetrics fm = g2.getFontMetrics();
					final String name = truncate(item.fileName(), fm, getWidth());
					g2.drawString(name, (getWidth() - fm.stringWidth(name)) / 2, THUMB + 2 + fm.getAscent());
				}
			} finally {
				g2.dispose();
			}
		}
	}

	/** Couleur des cases d'attente. */
	private static Color placeholder() {
		final Color border = UIManager.getColor("Component.borderColor");
		return border != null ? border : Color.LIGHT_GRAY;
	}

	/** Image centrée dans box, proportions conservées (agrandie seulement si enlarge). */
	static void drawFitted(final Graphics2D g, final BufferedImage image, final Rectangle box, final boolean enlarge) {
		final Dimension size = fit(image.getWidth(), image.getHeight(), box.width, box.height, enlarge);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(image, box.x + (box.width - size.width) / 2, box.y + (box.height - size.height) / 2, size.width,
				size.height, null);
	}

	/** Taille de l'image ajustée au cadre, proportions conservées. */
	static Dimension fit(final int width, final int height, final int maxWidth, final int maxHeight, final boolean enlarge) {
		double ratio = Math.min((double) maxWidth / width, (double) maxHeight / height);
		if (!enlarge) {
			ratio = Math.min(ratio, 1);
		}
		return new Dimension(Math.max(1, (int) Math.round(width * ratio)), Math.max(1, (int) Math.round(height * ratio)));
	}

	/** Icône neutre « image » (cadre, soleil, montagne) dans box. */
	static void paintIcon(final Graphics2D g, final Rectangle box) {
		final Graphics2D g2 = (Graphics2D) g.create();
		try {
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(placeholder());
			g2.fillRoundRect(box.x, box.y, box.width, box.height, box.width / 8, box.width / 8);
			final int side = box.width / 2;
			final int x = box.x + (box.width - side) / 2;
			final int y = box.y + (box.height - side * 3 / 4) / 2;
			final int h = side * 3 / 4;
			g2.setColor(NEUTRAL);
			g2.setStroke(new BasicStroke(Math.max(1.5f, box.width / 48f)));
			g2.drawRoundRect(x, y, side, h, side / 8, side / 8);
			final int sun = side / 6;
			g2.fillOval(x + side - sun * 2, y + sun / 2 + 2, sun, sun);
			final Polygon mountain = new Polygon();
			mountain.addPoint(x + 2, y + h - 2);
			mountain.addPoint(x + side * 2 / 5, y + h / 3);
			mountain.addPoint(x + side * 3 / 5, y + h * 2 / 3);
			mountain.addPoint(x + side * 3 / 4, y + h / 2);
			mountain.addPoint(x + side - 2, y + h - 2);
			g2.fillPolygon(mountain);
		} finally {
			g2.dispose();
		}
	}

	/** Texte raccourci (« … ») pour tenir dans width pixels. */
	static String truncate(final String text, final FontMetrics fm, final int width) {
		if (fm.stringWidth(text) <= width) {
			return text;
		}
		final String ellipsis = "…";
		int end = text.length();
		while (end > 0 && fm.stringWidth(text.substring(0, end) + ellipsis) > width) {
			end--;
		}
		return text.substring(0, end) + ellipsis;
	}
}
