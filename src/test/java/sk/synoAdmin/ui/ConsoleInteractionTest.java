package sk.synoAdmin.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import sk.synoAdmin.ConsoleUtil;
import sk.synoAdmin.i18n.Language;
import sk.synoAdmin.i18n.Messages;

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
	void promptBool_reponsesAnglaisesEtFrancaisesToujoursAcceptees() {
		assertTrue(input("YES").promptBool("?"));
		assertTrue(input("y").promptBool("?"));
		assertTrue(input("Oui").promptBool("?"));
		assertFalse(input("no").promptBool("?"));
		assertFalse(input("N").promptBool("?"));
	}

	@Test
	void promptBool_indiceEtReponsesDeLaLangueCourante() {
		final PrintStream saved = System.out;
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
		try {
			assertTrue(input("yes").promptBool("Q"));
			Messages.setLanguage(Language.FR);
			assertFalse(input("non").promptBool("Q"));
		} finally {
			System.setOut(saved);
			Messages.setLanguage(Language.EN);
		}
		final String output = out.toString(StandardCharsets.UTF_8);
		assertTrue(output.contains("Q (yes/no)"));
		assertTrue(output.contains("Q (oui/non)"));
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
