package sk.synoAdmin.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import sk.synoAdmin.ConsoleUtil;

class ConsoleInteractionTest {

	private static ConsoleInteraction input(final String... lines) {
		return new ConsoleInteraction(new ByteArrayInputStream((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8)));
	}

	@AfterEach
	void restore() {
		ConsoleUtil.setInteraction(new ConsoleInteraction());
	}

	@Test
	void promptLine_ignoreLesLignesVides() {
		assertEquals("valeur", input("", "  valeur ").promptLine("?"));
	}

	@Test
	void promptLine_entreeConserveLaValeurParDefaut() {
		assertEquals("defaut", input("").promptLine("?", "defaut"));
	}

	@Test
	void promptChoices_numerosValidesSansDoublon() {
		assertEquals(List.of(1, 0), input("0", "2,1,2").promptChoices("?", List.of("a", "b")));
	}

	@Test
	void promptBool_ouiNon() {
		assertTrue(input("peut-être", "o").promptBool("?"));
		assertFalse(input("non").promptBool("?"));
	}

	@Test
	void promptSecretOrKeep_entreeRenvoieNull() {
		assertNull(input("").promptSecretOrKeep("?"));
	}

	@Test
	void consoleUtil_delegueALInteractionCourante() {
		ConsoleUtil.setInteraction(input("choix"));
		assertEquals("choix", ConsoleUtil.promptLine("?"));
		assertFalse(ConsoleUtil.interaction().isGraphical());
	}
}
