package sk.synoAdmin.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;

import javax.swing.JComponent;
import javax.swing.UIManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GuiLocaleTest {

	private Locale savedDisplay;
	private Locale savedComponent;

	@BeforeEach
	void save() {
		savedDisplay = Locale.getDefault(Locale.Category.DISPLAY);
		savedComponent = JComponent.getDefaultLocale();
	}

	@AfterEach
	void restore() {
		Gui.applyLocale(savedDisplay);
		JComponent.setDefaultLocale(savedComponent);
	}

	@Test
	void textesStandardDeSwing_dansLaLangueChoisie() {
		Gui.applyLocale(Locale.GERMAN);
		assertEquals("Ja", UIManager.getString("OptionPane.yesButtonText", JComponent.getDefaultLocale()));
		Gui.applyLocale(Locale.FRENCH);
		assertEquals("Oui", UIManager.getString("OptionPane.yesButtonText", JComponent.getDefaultLocale()));
		assertEquals(savedFormat(), Locale.getDefault(Locale.Category.FORMAT));
	}

	private static Locale savedFormat() {
		return Locale.getDefault(Locale.Category.FORMAT);
	}
}
