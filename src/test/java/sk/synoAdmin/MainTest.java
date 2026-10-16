package sk.synoAdmin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import sk.synoAdmin.ui.ConsoleInteraction;

class MainTest {

	@Test
	void ancienneSyntaxe_detectee() {
		assertTrue(Main.isLegacySyntax(new String[] { "-a", "FULL_DB_TO_FS", "-c", "x" }));
		assertTrue(Main.isLegacySyntax(new String[] { "--action=INIT" }));
		assertFalse(Main.isLegacySyntax(new String[] { "-c", "x", "pull" }));
	}

	@Test
	void erreurUtilisateur_routeeVersInteraction() {
		final List<String> errors = new ArrayList<>();
		ConsoleUtil.setInteraction(new ConsoleInteraction() {
			@Override
			public void error(final String message, final Throwable detail) {
				errors.add(message);
			}
		});
		try {
			assertEquals(1, Main.handleError(new UserException("Saisie annulée.")));
			assertEquals(List.of("Saisie annulée."), errors);
		} finally {
			ConsoleUtil.setInteraction(new ConsoleInteraction());
		}
	}
}
