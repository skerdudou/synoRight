package sk.synoAdmin.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;
import picocli.CommandLine.ParseResult;
import sk.synoAdmin.UserException;
import sk.synoAdmin.cli.SynoRightCommand;
import sk.synoAdmin.engine.RightEngine;
import sk.synoAdmin.prefs.Preferences;

class GuiCommandTest {

	@Test
	void normalizeFolderArg_guillemetFinal() {
		// "P:\" passé par l'Explorateur : CommandLineToArgvW donne P:"
		assertEquals("P:\\", GuiCommand.normalizeFolderArg("P:\""));
		assertEquals("\\\\NAS\\photo\\2024", GuiCommand.normalizeFolderArg("\\\\NAS\\photo\\2024"));
	}

	@Test
	void sousCommandes_rightsAvecConfigApresGui() {
		final SynoRightCommand root = new SynoRightCommand();
		final ParseResult result = new CommandLine(root).parseArgs("gui", "-c", "D:\\conf", "rights", "P:\\photo\\a");

		assertEquals(Paths.get("D:\\conf"), root.configDir());
		assertEquals("rights", result.subcommand().subcommand().commandSpec().name());
	}

	@Test
	void rights_plusieursDossiersEtRegroupement() {
		final ParseResult result = new CommandLine(new SynoRightCommand())
				.parseArgs("gui", "rights", "P:\\photo\\a", "P:\\photo\\b", "--collect");
		final GuiCommand.Rights rights = (GuiCommand.Rights) result.subcommand().subcommand().commandSpec().userObject();

		assertEquals(List.of("P:\\photo\\a", "P:\\photo\\b"), rights.folders);
		assertTrue(rights.collect);
	}

	@Test
	void rights_nouveauxDossiers_attenteParDefaut10Minutes() {
		final ParseResult result = new CommandLine(new SynoRightCommand())
				.parseArgs("gui", "rights", "--new-folders", "\\\\NAS\\photo\\2026", "\\\\NAS\\photo\\2026\\Vacances");
		final GuiCommand.Rights rights = (GuiCommand.Rights) result.subcommand().subcommand().commandSpec().userObject();

		assertTrue(rights.newFolders);
		assertEquals(Duration.ofMinutes(10), rights.indexWait());
		assertEquals(List.of("\\\\NAS\\photo\\2026", "\\\\NAS\\photo\\2026\\Vacances"), rights.folders);
	}

	@Test
	void nouveauxDossiers_tousHorsDuDossierPhotos_erreurExplicite(@TempDir final Path dir) throws IOException {
		final Path photos = Files.createDirectories(dir.resolve("photos"));
		final Path ailleurs = Files.createDirectories(dir.resolve("ailleurs/2026"));
		final RightEngine engine = new RightEngine(new Preferences(dir.resolve("conf"), photos.toString(), "http://localhost", "u", "p", List.of(), false));

		final UserException e = assertThrows(UserException.class,
				() -> GuiCommand.Rights.newFolderSelection(engine, List.of(ailleurs.toString())));
		assertTrue(e.getMessage().contains(ailleurs.toString()), e.getMessage());
	}

	@Test
	void nouveauxDossiers_seulementTropProfonds_rienSansErreur(@TempDir final Path dir) throws IOException {
		final Path photos = Files.createDirectories(dir.resolve("photos"));
		final Path profond = Files.createDirectories(photos.resolve("2026/Vacances/raw"));
		final RightEngine engine = new RightEngine(new Preferences(dir.resolve("conf"), photos.toString(), "http://localhost", "u", "p", List.of(), false));

		assertEquals(List.of(), GuiCommand.Rights.newFolderSelection(engine, List.of(profond.toString())));
	}

	@Test
	void rights_attenteIndexationReglable() {
		final ParseResult result = new CommandLine(new SynoRightCommand())
				.parseArgs("gui", "rights", "--new-folders", "--wait-index", "3", "P:\\a");
		final GuiCommand.Rights rights = (GuiCommand.Rights) result.subcommand().subcommand().commandSpec().userObject();

		assertEquals(Duration.ofMinutes(3), rights.indexWait());
	}

	@Test
	void rights_menuExplorateur_attendAussiLIndexation() {
		final ParseResult result = new CommandLine(new SynoRightCommand()).parseArgs("gui", "rights", "--collect", "P:\\a");
		final GuiCommand.Rights rights = (GuiCommand.Rights) result.subcommand().subcommand().commandSpec().userObject();

		assertFalse(rights.newFolders);
		assertEquals(Duration.ofMinutes(10), rights.indexWait());
	}

	@Test
	void rights_attenteReglableSansNouveauxDossiers() {
		final ParseResult result = new CommandLine(new SynoRightCommand()).parseArgs("gui", "rights", "--wait-index", "0", "P:\\a");
		final GuiCommand.Rights rights = (GuiCommand.Rights) result.subcommand().subcommand().commandSpec().userObject();

		assertEquals(Duration.ZERO, rights.indexWait());
	}

	@Test
	void grant_groupePuisDossiers() {
		final ParseResult result = new CommandLine(new SynoRightCommand())
				.parseArgs("gui", "grant", "famille", "P:\\photo\\a", "P:\"");
		final GuiCommand.Grant grant = (GuiCommand.Grant) result.subcommand().subcommand().commandSpec().userObject();

		assertEquals("famille", grant.group);
		assertFalse(grant.collect);
		assertEquals(List.of("P:\\photo\\a", "P:\\"), GuiCommand.normalizeFolderArgs(grant.folders));
	}

	@Test
	void normalizeFolderArgs_sansDoublon() {
		assertEquals(List.of("P:\\a", "P:\\b"), GuiCommand.normalizeFolderArgs(List.of("P:\\a", "P:\\b", "P:\\a")));
	}

	@Test
	void sync_optionsExclusives() {
		final ParseResult result = new CommandLine(new SynoRightCommand()).parseArgs("gui", "sync", "--push");
		final GuiCommand.Sync sync = (GuiCommand.Sync) result.subcommand().subcommand().commandSpec().userObject();

		assertTrue(sync.push);
		assertFalse(sync.pull);
	}
}
