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

import picocli.CommandLine;

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
	void langueDeLaConfiguration_avantLAide(@TempDir final Path dir) throws Exception {
		Files.writeString(dir.resolve(Preferences.CONFIG_FILENAME), "language=fr\n");
		final String saved = System.getProperty("synoRight.language");
		System.clearProperty("synoRight.language");
		try {
			Main.run(new String[] { "-c", dir.toString(), "--version" });
			final String fr = Main.newCommandLine().getSubcommands().get("pull").getUsageMessage();
			assertTrue(fr.contains("crée/supprime les fichiers .synoRight"), fr);
			assertTrue(fr.contains("Applique sans demander de confirmation."), fr);
			assertTrue(fr.contains("Utilisation : "), fr);
			assertTrue(fr.contains("<dossier>") && !fr.contains("<folder>"), fr);
			assertTrue(fr.contains("Affiche ce message d'aide et quitte."), fr);
			final CommandLine menuInstall = Main.newCommandLine().getSubcommands().get("menu").getSubcommands().get("install");
			assertTrue(menuInstall.getUsageMessage().contains("Installe (ou met à jour) le menu contextuel"), menuInstall.getUsageMessage());
		} finally {
			if (saved != null) {
				System.setProperty("synoRight.language", saved);
			}
			Messages.setLanguage(Language.EN);
		}
		final String en = Main.newCommandLine().getSubcommands().get("pull").getUsageMessage();
		assertTrue(en.contains("creates/deletes the .synoRight files"), en);
		assertTrue(en.contains("Usage: "), en);
		assertTrue(en.contains("<folder>") && !en.contains("<dossier>"), en);
		final String root = Main.newCommandLine().getUsageMessage();
		assertTrue(root.contains("Synchronizes Synology Photos album permissions"), root);
		assertTrue(root.contains("Configuration directory"), root);
	}

	@Test
	void menuRepair_aideDisponible() {
		final CommandLine repair = Main.newCommandLine().getSubcommands().get("menu").getSubcommands().get("repair");
		assertTrue(repair.getUsageMessage().contains("Repair the File Explorer menus after an update or a move of the application"), repair.getUsageMessage());
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
