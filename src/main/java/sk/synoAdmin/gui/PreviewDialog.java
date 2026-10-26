package sk.synoAdmin.gui;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.preview.PreviewImages.Preview;
import sk.synoAdmin.preview.PreviewItem;

/**
 * Aperçu agrandi (modal) d'un élément du bandeau : la vignette du bandeau agrandie en attendant l'image grand format,
 * chargée en arrière-plan ; au plus 80 % de l'écran ; ← → : élément précédent/suivant (en boucle) ; Échap ou clic :
 * fermeture. Sur l'EDT.
 */
final class PreviewDialog extends JDialog {
	private static final long serialVersionUID = 1L;

	// taille de l'aperçu sans image (attente, icône)
	private static final int EMPTY_SIDE = 320;
	private static final int ICON_SIDE = 160;

	/**
	 * Chargement de l'image grand format en arrière-plan ; onUi n'est appelé (sur l'EDT) que si wanted est encore vrai.
	 */
	@FunctionalInterface
	interface LargeLoader {
		void loadLarge(PreviewItem item, int maxSide, BooleanSupplier wanted, Consumer<Preview> onUi);
	}

	private final transient List<PreviewItem> items;
	private final transient IntFunction<Optional<Preview>> stripPreview;
	private final transient LargeLoader loader;
	// une demande dépassée (flèches pressées vite, fermeture) n'affiche rien
	private final AtomicInteger generation = new AtomicInteger();
	private final Dimension max;
	private final ImagePanel panel = new ImagePanel();
	private int index;

	private PreviewDialog(final Window owner, final List<PreviewItem> items, final IntFunction<Optional<Preview>> stripPreview,
			final LargeLoader loader) {
		super(owner, ModalityType.DOCUMENT_MODAL);
		this.items = items;
		this.stripPreview = stripPreview;
		this.loader = loader;
		this.max = maxSize(owner);
		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		setContentPane(panel);
		panel.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(final MouseEvent e) {
				dispose();
			}
		});
		bind(KeyEvent.VK_ESCAPE, "close", this::dispose);
		bind(KeyEvent.VK_LEFT, "previous", () -> select(index - 1));
		bind(KeyEvent.VK_RIGHT, "next", () -> select(index + 1));
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(final WindowEvent e) {
				generation.incrementAndGet(); // chargement en cours ignoré
			}
		});
	}

	/**
	 * Ouvre l'aperçu sur l'élément index et attend sa fermeture.
	 *
	 * @param stripPreview aperçu déjà affiché dans le bandeau pour un indice (vide s'il est encore en attente)
	 */
	static void open(final Window owner, final List<PreviewItem> items, final IntFunction<Optional<Preview>> stripPreview,
			final int index, final LargeLoader loader) {
		final PreviewDialog dialog = new PreviewDialog(owner, items, stripPreview, loader);
		dialog.select(index);
		dialog.setVisible(true);
	}

	private void bind(final int key, final String name, final Runnable action) {
		panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, 0), name);
		panel.getActionMap().put(name, new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(final ActionEvent e) {
				action.run();
			}
		});
	}

	// 80 % de la zone utilisable de l'écran de la fenêtre
	private static Dimension maxSize(final Window owner) {
		final GraphicsConfiguration gc = owner.getGraphicsConfiguration();
		final Rectangle bounds = gc.getBounds();
		final Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
		return new Dimension((bounds.width - insets.left - insets.right) * 4 / 5,
				(bounds.height - insets.top - insets.bottom) * 4 / 5);
	}

	/** Affiche l'élément i (en boucle) : vignette du bandeau agrandie, puis image grand format. */
	private void select(final int i) {
		index = Math.floorMod(i, items.size());
		final int gen = generation.incrementAndGet();
		final PreviewItem item = items.get(index);
		setTitle(item.fileName());
		panel.show(item, stripPreview.apply(index).flatMap(Preview::image).orElse(null), true);
		resize();
		loader.loadLarge(item, Math.max(max.width, max.height), () -> generation.get() == gen, preview -> {
			panel.show(item, largeOrShown(preview, panel.image), false);
			resize();
		});
	}

	/**
	 * Image à garder une fois le grand format chargé : la sienne, sinon (icône) la vignette du bandeau déjà affichée.
	 */
	static BufferedImage largeOrShown(final Preview large, final BufferedImage shown) {
		return large.image().orElse(shown);
	}

	private void resize() {
		panel.setPreferredSize(panel.size(max));
		pack();
		setLocationRelativeTo(getOwner());
	}

	/** Image ajustée au panneau, ou texte d'attente, ou icône et nom du fichier. */
	private static final class ImagePanel extends JComponent {
		private static final long serialVersionUID = 1L;

		private transient PreviewItem item;
		private transient BufferedImage image;
		private boolean loading;

		ImagePanel() {
			setOpaque(true);
			setBackground(UIManager.getColor("Panel.background"));
			setForeground(UIManager.getColor("Label.foreground"));
			setFont(UIManager.getFont("Label.font"));
		}

		void show(final PreviewItem shownItem, final BufferedImage shownImage, final boolean stillLoading) {
			item = shownItem;
			image = shownImage;
			loading = stillLoading;
			repaint();
		}

		Dimension size(final Dimension max) {
			if (image == null) {
				return new Dimension(EMPTY_SIDE, EMPTY_SIDE);
			}
			return PreviewStrip.fit(image.getWidth(), image.getHeight(), max.width, max.height, true);
		}

		@Override
		protected void paintComponent(final Graphics g) {
			final Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setColor(getBackground());
				g2.fillRect(0, 0, getWidth(), getHeight());
				g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				final FontMetrics fm = g2.getFontMetrics();
				g2.setColor(getForeground());
				if (image != null) {
					PreviewStrip.drawFitted(g2, image, new Rectangle(0, 0, getWidth(), getHeight()), true);
				} else if (loading) {
					final String text = Messages.get(PreviewStrip.LOADING);
					g2.drawString(text, (getWidth() - fm.stringWidth(text)) / 2, (getHeight() + fm.getAscent()) / 2);
				} else if (item != null) {
					final Rectangle box = new Rectangle((getWidth() - ICON_SIDE) / 2, (getHeight() - ICON_SIDE) / 2 - fm.getHeight(),
							ICON_SIDE, ICON_SIDE);
					PreviewStrip.paintIcon(g2, box);
					g2.setColor(getForeground());
					final String name = PreviewStrip.truncate(item.fileName(), fm, getWidth() - 16);
					g2.drawString(name, (getWidth() - fm.stringWidth(name)) / 2, box.y + box.height + 8 + fm.getAscent());
				}
			} finally {
				g2.dispose();
			}
		}
	}
}
