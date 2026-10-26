package sk.synoAdmin.preview;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import sk.synoAdmin.LogUtil;
import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.preview.PreviewImages.Preview;
import sk.synoAdmin.preview.PreviewImages.Source;
import sk.synoAdmin.synology.SynoApiClient;
import sk.synoAdmin.synology.SynoPhotoItem;

/**
 * Produit l'image d'un élément : vignette Synology, sinon fichier réduit, sinon icône (image vide). Ne lève jamais d'exception.
 */
public final class PreviewLoader {
	private static final String SMALL = "sm";
	private static final String LARGE = "xl";
	// séparateur des raisons (diagnostic), sans typographie propre à une langue
	private static final String SEPARATOR = "; ";

	/**
	 * Téléchargement d'une vignette (remplaçable en test).
	 */
	@FunctionalInterface
	public interface ThumbnailFetcher {
		Optional<byte[]> fetch(int unitId, String cacheKey, String size);
	}

	private final ThumbnailFetcher fetcher;

	public PreviewLoader(final ThumbnailFetcher fetcher) {
		this.fetcher = fetcher;
	}

	public PreviewLoader(final SynoApiClient client) {
		this(client::thumbnail);
	}

	/**
	 * Image du bandeau : vignette sm, puis fichier (20 Mo au plus), puis icône.
	 */
	public Preview load(final PreviewItem item) {
		return load(item, SMALL, PreviewImages.THUMB_SIZE, PreviewImages.MAX_FILE_BYTES);
	}

	/**
	 * Image de l'aperçu agrandi : vignette xl, puis fichier de toute taille (lu sous-échantillonné), puis icône.
	 */
	public Preview loadLarge(final PreviewItem item, final int maxSide) {
		try {
			return load(item, LARGE, maxSide, Long.MAX_VALUE);
		} catch (final OutOfMemoryError e) {
			// ici seulement : l'aperçu agrandi lit des fichiers de toute taille (le bandeau s'arrête à 20 Mo) ;
			// l'image en cours de décodage est abandonnée, la mémoire redevient disponible
			LogUtil.debug("Large preview too big for memory " + item.file() + " : " + e);
			return icon(item, e.toString());
		}
	}

	private Preview load(final PreviewItem item, final String size, final int maxSide, final long maxBytes) {
		final String reason;
		final Optional<SynoPhotoItem.Thumb> thumb = item.thumb();
		if (thumb.isPresent() && thumb.get().readySizes().contains(size)) {
			final Optional<BufferedImage> image = fetcher
					.fetch(thumb.get().unitId(), thumb.get().cacheKey(), size)
					.flatMap(bytes -> PreviewImages.decode(bytes, maxSide));
			if (image.isPresent()) {
				return new Preview(item, image, Source.THUMBNAIL, "");
			}
			reason = Messages.get("cli.previews.reason.thumbFailed");
		} else if (thumb.isPresent()) {
			reason = Messages.get("cli.previews.reason.thumbNotReady");
		} else {
			// fichier du partage pas encore indexé par Synology Photos
			reason = Messages.get("cli.previews.reason.notInPhotos");
		}

		final Path file = item.file();
		if (!PreviewImages.readable(file)) {
			return icon(item, reason + SEPARATOR + Messages.get("cli.previews.reason.notReadable"));
		}
		final long length = length(file);
		if (length < 0) {
			return icon(item, reason + SEPARATOR + Messages.get("cli.previews.reason.missing"));
		}
		if (length > maxBytes) {
			return icon(item, reason + SEPARATOR
					+ Messages.get("cli.previews.reason.tooLarge", Long.toString(length / (1024 * 1024))));
		}
		final Optional<BufferedImage> image = PreviewImages.read(file, maxSide, maxBytes);
		if (image.isPresent()) {
			return new Preview(item, image, Source.FILE, reason);
		}
		return icon(item, reason + SEPARATOR + Messages.get("cli.previews.reason.decodeFailed"));
	}

	private static Preview icon(final PreviewItem item, final String reason) {
		return new Preview(item, Optional.empty(), Source.ICON, reason);
	}

	/** Taille du fichier, -1 s'il est absent. */
	private static long length(final Path file) {
		try {
			return Files.isRegularFile(file) ? Files.size(file) : -1;
		} catch (final IOException | RuntimeException e) {
			return -1;
		}
	}
}
