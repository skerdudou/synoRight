package sk.synoAdmin.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.preview.PreviewImages.Preview;
import sk.synoAdmin.preview.PreviewImages.Source;
import sk.synoAdmin.synology.SynoPhotoItem;

class PreviewLoaderTest {
	@TempDir
	Path tmp;

	private static PreviewItem item(final Path file, final Set<String> ready) {
		return new PreviewItem(file.getFileName().toString(), file,
				Optional.of(new SynoPhotoItem.Thumb(1, "key", ready)));
	}

	@Test
	void thumbnailReady_isThumbnail() throws IOException {
		final byte[] bytes = PreviewImagesTest.jpeg(300, 300);
		final PreviewLoader loader = new PreviewLoader(
				(id, key, size) -> "sm".equals(size) ? Optional.of(bytes) : Optional.empty());

		final Preview preview = loader.load(item(tmp.resolve("a.jpg"), Set.of("sm")));

		assertEquals(Source.THUMBNAIL, preview.source());
		assertEquals(96, preview.image().orElseThrow().getWidth());
	}

	@Test
	void thumbnailMissing_usesFile() throws IOException {
		final Path file = tmp.resolve("a.jpg");
		Files.write(file, PreviewImagesTest.jpeg(300, 300));
		final PreviewLoader loader = new PreviewLoader((id, key, size) -> Optional.empty());

		final Preview preview = loader.load(item(file, Set.of()));

		assertEquals(Source.FILE, preview.source());
		assertFalse(preview.reason().isBlank());
	}

	@Test
	void thumbnailDownloadFails_usesFile() throws IOException {
		final Path file = tmp.resolve("a.jpg");
		Files.write(file, PreviewImagesTest.jpeg(300, 300));
		final PreviewLoader loader = new PreviewLoader((id, key, size) -> Optional.empty());

		assertEquals(Source.FILE, loader.load(item(file, Set.of("sm"))).source());
	}

	@Test
	void thumbnailMissing_heic_isIcon() throws IOException {
		final Path file = tmp.resolve("a.heic");
		Files.write(file, new byte[10]);
		final PreviewLoader loader = new PreviewLoader((id, key, size) -> Optional.empty());

		final Preview preview = loader.load(item(file, Set.of()));

		assertEquals(Source.ICON, preview.source());
		assertTrue(preview.image().isEmpty());
		assertFalse(preview.reason().isBlank());
	}

	@Test
	void strip_bigFileIsIcon_largeReadsIt() throws IOException {
		final Path file = tmp.resolve("big.jpg");
		Files.write(file, PreviewImagesTest.jpeg(2000, 1000));
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
			raf.setLength(25L * 1024 * 1024); // octets nuls après la fin du JPEG
		}
		final PreviewLoader loader = new PreviewLoader((id, key, size) -> Optional.empty());
		final PreviewItem item = new PreviewItem("big.jpg", file, Optional.empty());

		assertEquals(Source.ICON, loader.load(item).source());

		final Preview large = loader.loadLarge(item, 800);
		assertEquals(Source.FILE, large.source());
		assertEquals(800, large.image().orElseThrow().getWidth());
	}

	@Test
	void notInPhotos_reasonSaysSo() throws IOException {
		final Path file = tmp.resolve("a.heic");
		Files.write(file, new byte[10]);
		final PreviewLoader loader = new PreviewLoader((id, key, size) -> Optional.empty());

		final Preview preview = loader.load(new PreviewItem("a.heic", file, Optional.empty()));

		assertEquals(Source.ICON, preview.source());
		assertEquals(Messages.get("cli.previews.reason.notInPhotos") + "; " + Messages.get("cli.previews.reason.notReadable"),
				preview.reason());
	}

	@Test
	void thumbnailNotReady_reasonsSeparatedWithoutFrenchSpace() throws IOException {
		final Path file = tmp.resolve("a.heic");
		Files.write(file, new byte[10]);
		final PreviewLoader loader = new PreviewLoader((id, key, size) -> Optional.empty());

		final Preview preview = loader.load(item(file, Set.of()));

		assertEquals(Messages.get("cli.previews.reason.thumbNotReady") + "; " + Messages.get("cli.previews.reason.notReadable"),
				preview.reason());
	}

	@Test
	void large_outOfMemory_isIcon() {
		final PreviewLoader loader = new PreviewLoader((id, key, size) -> {
			throw new OutOfMemoryError("Java heap space");
		});

		final Preview preview = loader.loadLarge(item(tmp.resolve("a.jpg"), Set.of("xl")), 800);

		assertEquals(Source.ICON, preview.source());
		assertTrue(preview.image().isEmpty());
	}

	@Test
	void large_usesXlThumbnail() throws IOException {
		final byte[] bytes = PreviewImagesTest.jpeg(1600, 800);
		final PreviewLoader loader = new PreviewLoader(
				(id, key, size) -> "xl".equals(size) ? Optional.of(bytes) : Optional.empty());

		final Preview preview = loader.loadLarge(item(tmp.resolve("a.jpg"), Set.of("sm", "xl")), 800);

		assertEquals(Source.THUMBNAIL, preview.source());
		assertEquals(800, preview.image().orElseThrow().getWidth());
	}
}
