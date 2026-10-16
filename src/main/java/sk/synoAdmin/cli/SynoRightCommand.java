package sk.synoAdmin.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;
import sk.synoAdmin.gui.GuiCommand;
import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.prefs.Preferences;

/**
 * Commande racine : options communes à toutes les sous-commandes.
 */
@Command(name = "synoRight", scope = ScopeType.INHERIT, mixinStandardHelpOptions = true,
		versionProvider = SynoRightCommand.VersionProvider.class,
		subcommands = {
				InitCommand.class,
				RightCommands.Status.class,
				RightCommands.Pull.class,
				RightCommands.Push.class,
				RightCommands.Show.class,
				RightCommands.Grant.class,
				RightCommands.Revoke.class,
				RightsCommand.class,
				ConfigCommands.Config.class,
				ConfigCommands.Groups.class,
				ConfigCommands.Password.class,
				ConfigCommands.Logout.class,
				ConfigCommands.Menu.class,
				GuiCommand.class })
public class SynoRightCommand implements Callable<Integer> {

	@Option(names = { "-c", "--config" }, scope = ScopeType.INHERIT, paramLabel = "<folder>",
			descriptionKey = "cli.opt.config")
	Path configDir;

	@Option(names = { "-v", "--verbose" }, scope = ScopeType.INHERIT,
			descriptionKey = "cli.opt.verbose")
	boolean verbose;

	// lue par Main : pause avant fermeture de la fenêtre ouverte par le menu contextuel
	@Option(names = "--pause", scope = ScopeType.INHERIT, hidden = true,
			descriptionKey = "cli.opt.pause")
	boolean pause;

	@Spec
	CommandSpec spec;

	/** Répertoire de configuration demandé (option -c), ou null. */
	public Path configDir() {
		return configDir;
	}

	@Override
	public Integer call() {
		spec.commandLine().usage(System.out);
		if (!Preferences.exists(Preferences.resolveConfigDir(configDir))) {
			System.out.println();
			System.out.println(Messages.get("cli.main.noConfig"));
		}
		return 0;
	}

	public static final class VersionProvider implements IVersionProvider {
		@Override
		public String[] getVersion() {
			final String version = SynoRightCommand.class.getPackage().getImplementationVersion();
			return new String[] { "synoRight " + (version == null ? Messages.get("cli.version.development") : version) };
		}
	}
}
