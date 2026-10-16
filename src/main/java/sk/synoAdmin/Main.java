package sk.synoAdmin;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.util.Arrays;

import picocli.CommandLine;
import sk.synoAdmin.cli.LocalizedHelp;
import sk.synoAdmin.cli.SynoRightCommand;
import sk.synoAdmin.i18n.Language;
import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.ui.Interaction;

public class Main {

	public static void main(final String[] args) {
		final int exitCode = run(args);

		// pause seulement pour la fenêtre du menu contextuel (et pour les anciens menus, qui doivent afficher le message)
		if (isLegacySyntax(args) || Arrays.asList(args).contains("--pause")) {
			ConsoleUtil.pauseBeforeExit();
		}

		if (exitCode != 0) {
			System.exit(exitCode);
		}
	}

	/** Exécute la ligne de commande et renvoie le code de sortie (sans quitter la JVM). */
	static int run(final String[] args) {
		final long start = System.nanoTime();
		final int exitCode;

		if (isLegacySyntax(args)) {
			initLanguage(args);
			System.err.println(Messages.get("main.legacySyntax.obsolete"));
			System.err.println(Messages.get("main.legacySyntax.reinstall"));
			exitCode = 2;
		} else {
			initLanguage(args);
			exitCode = newCommandLine().execute(args);
		}

		if (LogUtil.isVerbose()) {
			System.out.println("Total time : " + (System.nanoTime() - start) / 1_000_000 + " ms");
		}
		return exitCode;
	}

	/** Construit la ligne de commande, textes d'aide dans la langue courante (à appeler après {@link #initLanguage}). */
	static CommandLine newCommandLine() {
		// picocli écrit par défaut avec l'encodage de la JVM (UTF-8) : on reprend celui de la console
		return new CommandLine(new SynoRightCommand())
				.setResourceBundle(Messages.bundle())
				.setHelpFactory(LocalizedHelp::new)
				.setOut(new PrintWriter(new OutputStreamWriter(System.out, System.out.charset()), true))
				.setErr(new PrintWriter(new OutputStreamWriter(System.err, System.err.charset()), true))
				.setSubcommandsCaseInsensitive(true)
				.setExecutionExceptionHandler((e, cmd, parseResult) -> handleError(e));
	}

	// la langue doit être connue avant picocli (aide, erreurs d'analyse) : on lit -c / --config à la main
	private static void initLanguage(final String[] args) {
		String dir = null;
		for (int i = 0; i < args.length; i++) {
			if ((args[i].equals("-c") || args[i].equals("--config")) && i + 1 < args.length) {
				dir = args[i + 1];
				break;
			}
			if (args[i].startsWith("--config=")) {
				dir = args[i].substring("--config=".length());
				break;
			}
		}
		String setting = Preferences.LANGUAGE_AUTO;
		try {
			setting = Preferences.readLanguageSetting(Preferences.resolveConfigDir(dir == null ? null : Paths.get(dir)));
		} catch (final RuntimeException e) {
			// chemin invalide : on garde la langue automatique
		}
		Messages.setLanguage(Language.resolve(setting));
	}

	// erreur prévue : message seul ; imprévue : message, détail en mode verbeux (toujours dans l'IHM)
	static int handleError(final Exception e) {
		final Interaction ui = ConsoleUtil.interaction();
		if (e instanceof UserException) {
			ui.error(e.getMessage(), LogUtil.isVerbose() ? e.getCause() : null);
		} else if (ui.isGraphical()) {
			ui.error(Messages.get("main.error.unexpected", e), e);
		} else {
			ui.error(Messages.get("main.error.unexpected", e), LogUtil.isVerbose() ? e : null);
			if (!LogUtil.isVerbose()) {
				System.err.println(Messages.get("main.error.verboseHint"));
			}
		}
		return 1;
	}

	// ancienne syntaxe "-a ACTION", encore utilisée par les menus contextuels installés avant les sous-commandes
	static boolean isLegacySyntax(final String[] args) {
		return Arrays.stream(args).anyMatch(a -> a.equals("-a") || a.equals("--action") || a.startsWith("--action="));
	}
}
