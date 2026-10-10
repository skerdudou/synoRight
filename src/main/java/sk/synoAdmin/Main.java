package sk.synoAdmin;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Arrays;

import picocli.CommandLine;
import sk.synoAdmin.cli.SynoRightCommand;

public class Main {

	public static void main(final String[] args) {
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

		ConsoleUtil.pauseBeforeExit();

		if (exitCode != 0) {
			System.exit(exitCode);
		}
	}

	// erreur prévue : message seul ; imprévue : message, stack trace en mode verbeux
	private static int handleError(final Exception e) {
		if (e instanceof UserException) {
			System.err.println(e.getMessage());
			if (LogUtil.isVerbose() && e.getCause() != null) {
				e.getCause().printStackTrace();
			}
		} else {
			System.err.println("Erreur inattendue : " + e);
			if (LogUtil.isVerbose()) {
				e.printStackTrace();
			} else {
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
