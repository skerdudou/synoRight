package sk.synoAdmin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sk.synoAdmin.i18n.Language;
import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.ui.ConsoleInteraction;

class MainTest {

	@Test
	void ancienneSyntaxe_detectee() {
		assertTrue(Main.isLegacySyntax(new String[] { "-a", "FULL_DB_TO_FS", "-c", "x" }));
		assertTrue(Main.isLegacySyntax(new String[] { "--action=INIT" }));
		assertFalse(Main.isLegacySyntax(new String[] { "-c", "x", "pull" }));
	}

	@Test
	void langueDeLaConfiguration(@TempDir final Path dir) throws Exception {
		Files.writeString(dir.resolve(Preferences.CONFIG_FILENAME), "language=fr\n");
		final String saved = System.getProperty("synoRight.language");
		System.clearProperty("synoRight.language");
		try {
			Main.run(new String[] { "-c", dir.toString(), "--version" });
			assertEquals(Language.FR, Messages.language());
		} finally {
			if (saved != null) {
				System.setProperty("synoRight.language", saved);
			}
			Messages.setLanguage(Language.EN);
		}
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
