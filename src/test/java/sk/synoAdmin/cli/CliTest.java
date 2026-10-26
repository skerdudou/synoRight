package sk.synoAdmin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;
import picocli.CommandLine.ParseResult;
import sk.synoAdmin.menu.MenuTarget;

class CliTest {

	private static ParseResult parse(final SynoRightCommand root, final String... args) {
		return new CommandLine(root).parseArgs(args);
	}

	@Test
	void optionsCommunes_avantOuApresLaSousCommande() {
		// syntaxe du menu contextuel : options avant la sous-commande
		final SynoRightCommand before = new SynoRightCommand();
		parse(before, "-c", "D:\\conf", "-v", "pull");
		assertEquals(Paths.get("D:\\conf"), before.configDir);
		assertTrue(before.verbose);

		// options après la sous-commande : appliquées à la commande racine
		final SynoRightCommand after = new SynoRightCommand();
		parse(after, "push", "-c", "D:\\conf", "--verbose");
		assertEquals(Paths.get("D:\\conf"), after.configDir);
		assertTrue(after.verbose);
	}

	@Test
	void pause_accepteeAvantLaSousCommande() {
		final SynoRightCommand root = new SynoRightCommand();
		parse(root, "-c", "D:\\conf", "--pause", "show", "D:\\photo\\a");

		assertTrue(root.pause);
	}

	@Test
	void grant_groupeEtDossier() {
		final ParseResult result = parse(new SynoRightCommand(), "grant", "famille", "\\\\NAS\\photo\\2024");
		final RightCommands.Grant grant = (RightCommands.Grant) result.subcommand().commandSpec().userObject();

		assertEquals("famille", grant.group);
		assertEquals(List.of("\\\\NAS\\photo\\2024"), grant.folders);
	}

	@Test
	void grant_plusieursDossiers() {
		final ParseResult result = parse(new SynoRightCommand(), "grant", "famille", "\\\\NAS\\photo\\2024", "\\\\NAS\\photo\\2025");
		final RightCommands.Grant grant = (RightCommands.Grant) result.subcommand().commandSpec().userObject();

		assertEquals("famille", grant.group);
		assertEquals(List.of("\\\\NAS\\photo\\2024", "\\\\NAS\\photo\\2025"), grant.folders);
	}

	@Test
	void grant_dossierParDefautRepertoireCourant() {
		final ParseResult result = parse(new SynoRightCommand(), "grant", "famille");
		final RightCommands.Grant grant = (RightCommands.Grant) result.subcommand().commandSpec().userObject();

		assertEquals(List.of("."), grant.folders);
	}

	@Test
	void pull_optionsDryRunEtYes() {
		final ParseResult result = parse(new SynoRightCommand(), "pull", "-n", "-y");
		final RightCommands.Pull pull = (RightCommands.Pull) result.subcommand().commandSpec().userObject();

		assertTrue(pull.dryRun);
		assertTrue(pull.yes);
	}

	@Test
	void sousCommandesImbriquees() {
		final ParseResult result = parse(new SynoRightCommand(), "groups", "add");

		assertEquals("add", result.subcommand().subcommand().commandSpec().name());
	}

	@Test
	void menuInstall_cibleExplicite() {
		final ParseResult result = parse(new SynoRightCommand(), "menu", "install", "--classic");
		final ConfigCommands.MenuInstall install = (ConfigCommands.MenuInstall) result.subcommand().subcommand().commandSpec().userObject();

		assertEquals(MenuTarget.CLASSIC, install.target());
	}

	@Test
	void previews_hiddenFromGeneralHelpButOwnHelpWorks() {
		final CommandLine cli = new CommandLine(new SynoRightCommand());

		assertFalse(cli.getUsageMessage().contains("previews"));
		assertTrue(cli.getSubcommands().get("previews").getUsageMessage().contains("previews"));
		final ParseResult result = cli.parseArgs("previews", "D:\\photo\\a");
		assertEquals("D:\\photo\\a", ((PreviewsCommand) result.subcommand().commandSpec().userObject()).folder);
	}

}
