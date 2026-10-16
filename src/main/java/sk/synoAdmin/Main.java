package sk.synoAdmin;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Arrays;

import picocli.CommandLine;
import sk.synoAdmin.cli.SynoRightCommand;
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
			System.err.println("Syntaxe obsolète (-a <ACTION>) : l'outil utilise maintenant des sous-commandes "
					+ "(init, status, pull, push, grant, revoke...), voir --help.");
			System.err.println("Si ce message vient du menu contextuel, le réinstaller : synoRight menu install");
			exitCode = 2;
		} else {
			// picocli écrit par défaut avec l'encodage de la JVM (UTF-8) : on reprend celui de la console
			final CommandLine commandLine = new CommandLine(new SynoRightCommand())
					.setOut(new PrintWriter(new OutputStreamWriter(System.out, System.out.charset()), true))
					.setErr(new PrintWriter(new OutputStreamWriter(System.err, System.err.charset()), true))
					.setSubcommandsCaseInsensitive(true)
					.setExecutionExceptionHandler((e, cmd, parseResult) -> handleError(e));
			exitCode = commandLine.execute(args);
		}

		if (LogUtil.isVerbose()) {
			System.out.println("Total time : " + (System.nanoTime() - start) / 1_000_000 + " ms");
		}
		return exitCode;
	}

	// erreur prévue : message seul ; imprévue : message, détail en mode verbeux (toujours dans l'IHM)
	static int handleError(final Exception e) {
		final Interaction ui = ConsoleUtil.interaction();
		if (e instanceof UserException) {
			ui.error(e.getMessage(), LogUtil.isVerbose() ? e.getCause() : null);
		} else if (ui.isGraphical()) {
			ui.error("Erreur inattendue : " + e, e);
		} else {
			ui.error("Erreur inattendue : " + e, LogUtil.isVerbose() ? e : null);
			if (!LogUtil.isVerbose()) {
				System.err.println("(relancer avec -v pour le détail)");
			}
		}
		return 1;
	}

	// ancienne syntaxe "-a ACTION", encore utilisée par les menus contextuels installés avant les sous-commandes
	static boolean isLegacySyntax(final String[] args) {
		return Arrays.stream(args).anyMatch(a -> a.equals("-a") || a.equals("--action") || a.startsWith("--action="));
	}
}
