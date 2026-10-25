package sk.synoAdmin.gui;

import java.util.List;
import java.util.Optional;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import sk.synoAdmin.cli.AbstractCommand;
import sk.synoAdmin.engine.SyncPlan;
import sk.synoAdmin.menu.SelectionCollector;

/**
 * Sous-commande 'gui' (lanceur synoRightGui.exe et menu contextuel) : fenêtres Swing.
 * Sans sous-commande : fenêtre de configuration.
 */
@Command(name = "gui", hidden = true,
		subcommands = { GuiCommand.Config.class, GuiCommand.Rights.class, GuiCommand.Sync.class,
				GuiCommand.Grant.class, GuiCommand.Revoke.class, GuiCommand.Open.class })
public final class GuiCommand extends AbstractCommand {

	@Override
	protected void execute() {
		Gui.setup();
		Gui.showAndWait(Gui.onEdt(() -> ConfigWindow.create(configDir())));
	}

	/**
	 * Dossier reçu de l'Explorateur : "P:\" entre guillemets arrive sous la forme P:" (le \ final
	 * échappe le guillemet), on rétablit le chemin.
	 */
	static String normalizeFolderArg(final String folder) {
		return folder.endsWith("\"") ? folder.substring(0, folder.length() - 1) + "\\" : folder;
	}

	/** Dossiers reçus, rétablis et sans doublon, dans l'ordre. */
	static List<String> normalizeFolderArgs(final List<String> folders) {
		return folders.stream().map(GuiCommand::normalizeFolderArg).distinct().toList();
	}

	/** Sous-commande ouvrant une fenêtre et attendant sa fermeture. */
	abstract static class WindowCommand extends AbstractCommand {
		@Override
		protected final void execute() {
			Gui.setup();
			// construite sur l'EDT ; une UserException remonte telle quelle
			Gui.showAndWait(Gui.onEdt(this::createWindow));
		}

		abstract java.awt.Window createWindow();
	}

	/**
	 * Sous-commande portant sur les dossiers sélectionnés dans l'Explorateur. Avec --collect (menu classique,
	 * un processus par dossier), les dossiers des différents processus sont regroupés dans un seul.
	 */
	abstract static class FolderCommand extends AbstractCommand {
		@Option(names = "--collect", hidden = true)
		boolean collect;

		/**
		 * @param action identifie l'action, pour ne regrouper que les sélections de la même entrée de menu
		 * @return dossiers à traiter, vide s'ils ont été remis à un autre processus
		 */
		Optional<List<String>> folders(final List<String> args, final String action) {
			final List<String> folders = normalizeFolderArgs(args);
			if (!collect) {
				return Optional.of(folders);
			}
			return SelectionCollector.collect(configDir().toAbsolutePath() + "|" + action, folders).map(GuiCommand::normalizeFolderArgs);
		}
	}

	@Command(name = "config")
	static final class Config extends WindowCommand {
		@Override
		java.awt.Window createWindow() {
			return ConfigWindow.create(configDir());
		}
	}

	@Command(name = "rights")
	static final class Rights extends FolderCommand {
		@Parameters(index = "0..*", arity = "1..*", paramLabel = "<folder>")
		List<String> folders;

		@Override
		protected void execute() {
			final Optional<List<String>> all = folders(folders, "rights");
			if (all.isPresent()) {
				Gui.setup();
				Gui.showAndWait(Gui.onEdt(() -> RightsWindow.create(loadPrefs(), all.get())));
			}
		}
	}

	@Command(name = "sync")
	static final class Sync extends WindowCommand {
		@Option(names = "--push", descriptionKey = "cli.opt.guiPush")
		boolean push;
		@Option(names = "--pull", descriptionKey = "cli.opt.guiPull")
		boolean pull;

		@Override
		java.awt.Window createWindow() {
			return SyncPanel.createWindow(loadPrefs(), push ? SyncPlan.Direction.PUSH : pull ? SyncPlan.Direction.PULL : null);
		}
	}

	@Command(name = "grant")
	static final class Grant extends FolderCommand {
		@Parameters(index = "0", paramLabel = "<group>")
		String group;
		@Parameters(index = "1..*", arity = "1..*", paramLabel = "<folder>")
		List<String> folders;

		@Override
		protected void execute() {
			final Optional<List<String>> all = folders(folders, "grant|" + group);
			if (all.isPresent()) {
				Gui.setup();
				QuickActions.grant(loadPrefs(), group, all.get());
			}
		}
	}

	@Command(name = "revoke")
	static final class Revoke extends FolderCommand {
		@Parameters(index = "0", paramLabel = "<group>")
		String group;
		@Parameters(index = "1..*", arity = "1..*", paramLabel = "<folder>")
		List<String> folders;

		@Override
		protected void execute() {
			final Optional<List<String>> all = folders(folders, "revoke|" + group);
			if (all.isPresent()) {
				Gui.setup();
				QuickActions.revoke(loadPrefs(), group, all.get());
			}
		}
	}

	@Command(name = "open")
	static final class Open extends FolderCommand {
		@Parameters(index = "0..*", arity = "1..*", paramLabel = "<folder>")
		List<String> folders;

		@Override
		protected void execute() {
			final Optional<List<String>> all = folders(folders, "open");
			if (all.isPresent()) {
				Gui.setup();
				QuickActions.open(loadPrefs(), all.get());
			}
		}
	}
}
