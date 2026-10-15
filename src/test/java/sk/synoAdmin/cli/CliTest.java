package sk.synoAdmin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

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
		assertEquals("\\\\NAS\\photo\\2024", grant.folder);
	}

	@Test
	void grant_dossierParDefautRepertoireCourant() {
		final ParseResult result = parse(new SynoRightCommand(), "grant", "famille");
		final RightCommands.Grant grant = (RightCommands.Grant) result.subcommand().commandSpec().userObject();

		assertEquals(".", grant.folder);
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
	void normalizeUrl_ajouteHttpsEtRetireLeSlash() {
		assertEquals("https://nas:5001", InitCommand.normalizeUrl("nas:5001/"));
		assertEquals("http://nas:5000", InitCommand.normalizeUrl(" http://nas:5000 "));
		assertEquals("HTTPS://nas", InitCommand.normalizeUrl("HTTPS://nas"));
	}

}
