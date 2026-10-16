package sk.synoAdmin.gui;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import sk.synoAdmin.cli.AbstractCommand;
import sk.synoAdmin.engine.SyncPlan;

/**
 * Sous-commande 'gui' (lanceur synoRightGui.exe et menu contextuel) : fenêtres Swing.
 * Sans sous-commande : fenêtre de configuration.
 */
@Command(name = "gui", hidden = true, description = "Interface graphique (fenêtre de configuration par défaut).",
		subcommands = { GuiCommand.Config.class, GuiCommand.Rights.class, GuiCommand.Sync.class,
				GuiCommand.Grant.class, GuiCommand.Revoke.class, GuiCommand.Open.class })
public final class GuiCommand extends AbstractCommand {

	@Override
	protected void execute() {
		Gui.setup();
		Gui.showAndWait(ConfigWindow.create(configDir()));
	}

	/**
	 * Dossier reçu de l'Explorateur : "P:\" entre guillemets arrive sous la forme P:" (le \ final
	 * échappe le guillemet), on rétablit le chemin.
	 */
	static String normalizeFolderArg(final String folder) {
		return folder.endsWith("\"") ? folder.substring(0, folder.length() - 1) + "\\" : folder;
	}

	/** Sous-commande ouvrant une fenêtre et attendant sa fermeture. */
	abstract static class WindowCommand extends AbstractCommand {
		@Override
		protected final void execute() {
			Gui.setup();
			Gui.showAndWait(createWindow());
		}

		abstract java.awt.Window createWindow();
	}

	@Command(name = "config", description = "Fenêtre de configuration.")
	static final class Config extends WindowCommand {
		@Override
		java.awt.Window createWindow() {
			return ConfigWindow.create(configDir());
		}
	}

	@Command(name = "rights", description = "Droits d'un dossier.")
	static final class Rights extends WindowCommand {
		@Parameters(index = "0", paramLabel = "<dossier>")
		String folder;

		@Override
		java.awt.Window createWindow() {
			return RightsWindow.create(loadPrefs(), normalizeFolderArg(folder));
		}
	}

	@Command(name = "sync", description = "Synchronisation avec aperçu des écarts.")
	static final class Sync extends WindowCommand {
		@Option(names = "--push", description = "Met en avant la synchro fichiers -> Photos.")
		boolean push;
		@Option(names = "--pull", description = "Met en avant la synchro Photos -> fichiers.")
		boolean pull;

		@Override
		java.awt.Window createWindow() {
			return SyncPanel.createWindow(loadPrefs(), push ? SyncPlan.Direction.PUSH : pull ? SyncPlan.Direction.PULL : null);
		}
	}

	@Command(name = "grant", description = "Donne le droit à un groupe (résultat en dialogue).")
	static final class Grant extends AbstractCommand {
		@Parameters(index = "0", paramLabel = "<groupe>")
		String group;
		@Parameters(index = "1", paramLabel = "<dossier>")
		String folder;

		@Override
		protected void execute() {
			Gui.setup();
			QuickActions.grant(loadPrefs(), group, normalizeFolderArg(folder));
		}
	}

	@Command(name = "revoke", description = "Retire le droit à un groupe (résultat en dialogue).")
	static final class Revoke extends AbstractCommand {
		@Parameters(index = "0", paramLabel = "<groupe>")
		String group;
		@Parameters(index = "1", paramLabel = "<dossier>")
		String folder;

		@Override
		protected void execute() {
			Gui.setup();
			QuickActions.revoke(loadPrefs(), group, normalizeFolderArg(folder));
		}
	}

	@Command(name = "open", description = "Ouvre le dossier dans Synology Photos.")
	static final class Open extends AbstractCommand {
		@Parameters(index = "0", paramLabel = "<dossier>")
		String folder;

		@Override
		protected void execute() {
			Gui.setup();
			QuickActions.open(loadPrefs(), normalizeFolderArg(folder));
		}
	}
}
