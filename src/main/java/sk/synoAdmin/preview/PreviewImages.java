package sk.synoAdmin.preview;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import sk.synoAdmin.LogUtil;

/**
 * Décodage réduit des images d'aperçu : aucune méthode ne lève d'exception (image illisible ⇒ vide).
 */
public final class PreviewImages {
	/** Côté le plus long d'une vignette du bandeau. */
	public static final int THUMB_SIZE = 96;
	/** Taille maximale d'un fichier lu pour le bandeau. */
	public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

	private static final Set<String> READABLE = Set.of("jpg", "jpeg", "png", "gif", "bmp");

	/** Origine de l'image affichée. */
	public enum Source {
		THUMBNAIL, FILE, ICON
	}

	/**
	 * Aperçu d'un élément ; reason explique (pour le diagnostic) pourquoi la vignette n'a pas servi, vide pour une vignette.
	 */
	public record Preview(PreviewItem item, Optional<BufferedImage> image, Source source, String reason) {
	}

	private PreviewImages() {
		// utility
	}

	/**
	 * true si Java sait lire le format du fichier (d'après l'extension).
	 */
	public static boolean readable(final Path file) {
		final String name = file.getFileName().toString();
		final int dot = name.lastIndexOf('.');
		return dot >= 0 && READABLE.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	/**
	 * Lit un fichier image réduit à maxSide sur son plus grand côté ; vide si format non lisible, fichier absent,
	 * plus gros que maxBytes ({@link Long#MAX_VALUE} : sans limite) ou illisible.
	 */
	public static Optional<BufferedImage> read(final Path file, final int maxSide, final long maxBytes) {
		try {
			if (!readable(file) || !Files.isRegularFile(file) || Files.size(file) > maxBytes) {
				return Optional.empty();
			}
			try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
				return decode(in, maxSide);
			}
		} catch (final IOException | RuntimeException e) {
			LogUtil.debug("Image unreadable " + file + " : " + e);
			return Optional.empty();
		}
	}

	/**
	 * Décode des octets d'image (vignette du NAS) réduits à maxSide ; vide si ce n'est pas une image.
	 */
	public static Optional<BufferedImage> decode(final byte[] bytes, final int maxSide) {
		// flux en mémoire : pas de fichier temporaire par vignette (cache disque d'ImageIO)
		try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
			return decode(in, maxSide);
		} catch (final IOException | RuntimeException e) {
			LogUtil.debug("Image bytes unreadable : " + e);
			return Optional.empty();
		}
	}

	private static Optional<BufferedImage> decode(final ImageInputStream in, final int maxSide) throws IOException {
		if (in == null) {
			return Optional.empty();
		}
		final Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
		if (!readers.hasNext()) {
			return Optional.empty();
		}
		final ImageReader reader = readers.next();
		try {
			reader.setInput(in, true, true);
			final ImageReadParam param = reader.getDefaultReadParam();
			// sous-échantillonnage : on ne décode pas beaucoup plus de 2 × maxSide sur le grand côté
			final int longest = Math.max(reader.getWidth(0), reader.getHeight(0));
			final int step = Math.max(1, longest / (2 * maxSide));
			param.setSourceSubsampling(step, step, 0, 0);
			return Optional.ofNullable(scale(reader.read(0, param), maxSide));
		} finally {
			reader.dispose();
		}
	}

	/** Réduction de qualité (jamais d'agrandissement) ; null si l'image est vide. */
	private static BufferedImage scale(final BufferedImage source, final int maxSide) {
		if (source == null) {
			return null;
		}
		final int longest = Math.max(source.getWidth(), source.getHeight());
		if (longest <= maxSide) {
			return source;
		}
		final double ratio = (double) maxSide / longest;
		final int w = Math.max(1, (int) Math.round(source.getWidth() * ratio));
		final int h = Math.max(1, (int) Math.round(source.getHeight() * ratio));
		final BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = scaled.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(source, 0, 0, w, h, null);
		} finally {
			g.dispose();
		}
		return scaled;
	}
}
