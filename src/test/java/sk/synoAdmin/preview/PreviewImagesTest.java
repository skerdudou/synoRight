package sk.synoAdmin.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewImagesTest {
	@TempDir
	Path tmp;

	static byte[] jpeg(final int w, final int h) throws IOException {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", out);
		return out.toByteArray();
	}

	@Test
	void read_reducesLongestSide() throws IOException {
		final Path file = tmp.resolve("a.JPG");
		Files.write(file, jpeg(400, 200));

		final BufferedImage image = PreviewImages.read(file, 96, Long.MAX_VALUE).orElseThrow();

		assertEquals(96, image.getWidth());
		assertEquals(48, image.getHeight());
	}

	@Test
	void read_neverEnlarges() throws IOException {
		final Path file = tmp.resolve("a.png");
		final BufferedImage small = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
		ImageIO.write(small, "png", file.toFile());

		assertEquals(40, PreviewImages.read(file, 96, Long.MAX_VALUE).orElseThrow().getWidth());
	}

	@Test
	void read_tooLarge_empty() throws IOException {
		final Path file = tmp.resolve("a.jpg");
		Files.write(file, jpeg(400, 200));

		assertTrue(PreviewImages.read(file, 96, 10).isEmpty());
	}

	@Test
	void read_unsupportedExtension_empty() throws IOException {
		final Path file = tmp.resolve("a.heic");
		Files.write(file, jpeg(40, 20));

		assertFalse(PreviewImages.readable(file));
		assertTrue(PreviewImages.read(file, 96, Long.MAX_VALUE).isEmpty());
	}

	@Test
	void read_missingOrCorrupt_empty() throws IOException {
		final Path corrupt = tmp.resolve("b.jpg");
		Files.writeString(corrupt, "not an image");

		assertTrue(PreviewImages.read(corrupt, 96, Long.MAX_VALUE).isEmpty());
		assertTrue(PreviewImages.read(tmp.resolve("missing.jpg"), 96, Long.MAX_VALUE).isEmpty());
	}

	@Test
	void decode_bytes() throws IOException {
		assertEquals(96, PreviewImages.decode(jpeg(400, 200), 96).orElseThrow().getWidth());
		assertTrue(PreviewImages.decode("nope".getBytes(), 96).isEmpty());
	}
}
