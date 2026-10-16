package sk.synoAdmin;

import java.io.Console;
import java.io.InputStream;
import java.util.List;

import sk.synoAdmin.ui.ConsoleInteraction;
import sk.synoAdmin.ui.Interaction;

/**
 * Saisies utilisateur : délègue à l'{@link Interaction} courante (console par défaut).
 */
public final class ConsoleUtil {

	private static volatile Interaction interaction = new ConsoleInteraction();

	private ConsoleUtil() {
		// utility
	}

	public static Interaction interaction() {
		return interaction;
	}

	/**
	 * Remplace l'interaction courante (IHM, tests).
	 */
	public static void setInteraction(final Interaction newInteraction) {
		interaction = newInteraction;
	}

	/**
	 * Remplace l'entrée standard lue (tests).
	 */
	public static void setInput(final InputStream input) {
		interaction = new ConsoleInteraction(input);
	}

	public static String promptLine(final String label) {
		return interaction.promptLine(label);
	}

	public static String promptLine(final String label, final String defaultValue) {
		return interaction.promptLine(label, defaultValue);
	}

	public static List<Integer> promptChoices(final String label, final List<String> items) {
		return interaction.promptChoices(label, items);
	}

	public static String promptSecret(final String label) {
		return interaction.promptSecret(label);
	}

	public static String promptSecretOrKeep(final String label) {
		return interaction.promptSecretOrKeep(label);
	}

	public static boolean promptBool(final String label) {
		return interaction.promptBool(label);
	}

	public static void info(final String message) {
		interaction.info(message);
	}

	/**
	 * Attend Entrée avant de rendre la main, pour laisser lire la sortie quand la
	 * fenêtre de console va se fermer (lancement depuis le menu contextuel).
	 * Sans console interactive (script, flux redirigés), ne bloque pas.
	 */
	public static void pauseBeforeExit() {
		final Console console = System.console();
		if (console == null || !console.isTerminal()) {
			return;
		}
		System.out.println("Appuyer sur Entrée pour fermer...");
		console.readLine();
	}
}
