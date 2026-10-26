package sk.synoAdmin.gui;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import sk.synoAdmin.preview.PreviewImages.Preview;
import sk.synoAdmin.preview.PreviewImages.Source;
import sk.synoAdmin.preview.PreviewItem;

class PreviewDialogTest {
	private static final PreviewItem ITEM = new PreviewItem("1.jpg", Path.of("a", "1.jpg"), Optional.empty());

	@Test
	void largeImage_replacesStripImage() {
		final BufferedImage strip = new BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB);
		final BufferedImage large = new BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB);

		assertSame(large, PreviewDialog.largeOrShown(new Preview(ITEM, Optional.of(large), Source.FILE, ""), strip));
	}

	@Test
	void largeIcon_keepsStripImage() {
		final BufferedImage strip = new BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB);

		assertSame(strip, PreviewDialog.largeOrShown(new Preview(ITEM, Optional.empty(), Source.ICON, "x"), strip));
	}

	@Test
	void largeIcon_noStripImage_isIcon() {
		assertNull(PreviewDialog.largeOrShown(new Preview(ITEM, Optional.empty(), Source.ICON, "x"), null));
	}
}
