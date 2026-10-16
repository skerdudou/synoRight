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
import sk.synoAdmin.prefs.Preferences;

/**
 * Commande racine : options communes à toutes les sous-commandes.
 */
@Command(name = "synoRight", scope = ScopeType.INHERIT, mixinStandardHelpOptions = true,
		versionProvider = SynoRightCommand.VersionProvider.class,
		description = "Synchronise les droits des albums Synology Photos avec des fichiers @<groupe>.synoRight posés dans les dossiers photo.",
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

	@Option(names = { "-c", "--config" }, scope = ScopeType.INHERIT, paramLabel = "<dossier>",
			description = "Répertoire de la configuration (défaut : ~/.synoRight, ou le répertoire courant s'il en contient une).")
	Path configDir;

	@Option(names = { "-v", "--verbose" }, scope = ScopeType.INHERIT,
			description = "Affiche les temps de traitement et le détail des erreurs (aussi activable par verbose=true dans la configuration).")
	boolean verbose;

	// lue par Main : pause avant fermeture de la fenêtre ouverte par le menu contextuel
	@Option(names = "--pause", scope = ScopeType.INHERIT, hidden = true,
			description = "Attend Entrée avant de rendre la main (fenêtre ouverte par le menu contextuel).")
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
			System.out.println("Aucune configuration trouvée : commencer par 'synoRight init'.");
		}
		return 0;
	}

	public static final class VersionProvider implements IVersionProvider {
		@Override
		public String[] getVersion() {
			final String version = SynoRightCommand.class.getPackage().getImplementationVersion();
			return new String[] { "synoRight " + (version == null ? "(développement)" : version) };
		}
	}
}
