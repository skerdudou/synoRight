package sk.synoAdmin.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Image;
import java.util.List;

import org.junit.jupiter.api.Test;

class GuiIconsTest {

	@Test
	void icones_toutesLesTaillesEmbarquees() {
		final List<Image> icons = Gui.icons();

		assertEquals(6, icons.size());
		assertEquals(List.of(16, 32, 48, 64, 128, 256), icons.stream().map(i -> i.getWidth(null)).toList());
	}
}
