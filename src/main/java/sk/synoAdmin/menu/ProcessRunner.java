package sk.synoAdmin.menu;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;

/**
 * Exécution de commandes système (reg.exe, PowerShell) avec capture de la sortie.
 */
public final class ProcessRunner {

	public record Result(int exitCode, String output) {
		public boolean ok() {
			return exitCode == 0;
		}
	}

	private ProcessRunner() {
		// utility
	}

	public static Result run(final List<String> command) {
		try {
			final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			final String output = new String(process.getInputStream().readAllBytes(), Charset.forName(System.getProperty("native.encoding", "UTF-8")));
			return new Result(process.waitFor(), output.trim());
		} catch (final IOException e) {
			throw new RuntimeException("Impossible d'exécuter " + command.get(0), e);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
	}

	public static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
	}
}
