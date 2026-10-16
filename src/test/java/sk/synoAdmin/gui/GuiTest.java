package sk.synoAdmin.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JPanel;

import org.junit.jupiter.api.Test;

class GuiTest {

	@Test
	void desactivationsImbriquees_etatInitialRestaureALaDerniere() {
		final JPanel window = new JPanel();
		final JPanel section = new JPanel();
		final JButton active = new JButton("actif");
		final JButton inactive = new JButton("inactif");
		inactive.setEnabled(false);
		section.add(active);
		section.add(inactive);
		window.add(section);

		// tâche de la section, puis tâche de la fenêtre qui la chevauche ; la section termine la première
		final List<Component> sectionTask = Gui.disable(section);
		final List<Component> windowTask = Gui.disable(window);
		Gui.restore(sectionTask);
		assertFalse(active.isEnabled());
		assertFalse(section.isEnabled());

		Gui.restore(windowTask);
		assertTrue(active.isEnabled());
		assertTrue(section.isEnabled());
		assertTrue(window.isEnabled());
		assertFalse(inactive.isEnabled());
	}

	@Test
	void desactivationsImbriquees_fenetreTermineLaPremiere() {
		final JPanel window = new JPanel();
		final JButton active = new JButton("actif");
		window.add(active);

		final List<Component> windowTask = Gui.disable(window);
		final List<Component> buttonTask = Gui.disable(active);
		Gui.restore(windowTask);
		assertFalse(active.isEnabled());
		assertTrue(window.isEnabled());

		Gui.restore(buttonTask);
		assertTrue(active.isEnabled());
	}
}
