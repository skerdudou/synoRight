package sk.synoAdmin.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;
import picocli.CommandLine.ParseResult;
import sk.synoAdmin.cli.SynoRightCommand;

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
