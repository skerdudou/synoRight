package sk.synoAdmin;

import java.io.Console;
import java.util.Scanner;

/**
 * Saisies utilisateur sur la console.
 */
public final class ConsoleUtil {

	private static final Scanner STDIN = new Scanner(System.in);

	private ConsoleUtil() {
		// utility
	}

	/**
	 * Demande une valeur non vide (accepte les espaces).
	 */
	public static String promptLine(final String label) {
		String value;
		do {
			System.out.println(label);
			value = STDIN.nextLine().trim();
		} while (value.isEmpty());

		return value;
	}

	/**
	 * Demande une valeur sensible, saisie masquée quand une vraie console est disponible.
	 */
	public static String promptSecret(final String label) {
		final Console console = System.console();
		if (console != null && console.isTerminal()) {
			return new String(console.readPassword(label + " "));
		}
		return promptLine(label);
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

	/**
	 * Pose une question fermée oui/non.
	 */
	public static boolean promptBool(final String label) {
		while (true) {
			final String rep = promptLine(label + " (oui/non)");

			if (rep.equalsIgnoreCase("oui") || rep.equalsIgnoreCase("o")
					|| rep.equalsIgnoreCase("yes") || rep.equalsIgnoreCase("y")) {
				return true;
			}
			if (rep.equalsIgnoreCase("non") || rep.equalsIgnoreCase("n") || rep.equalsIgnoreCase("no")) {
				return false;
			}
			System.out.println("Répondre par oui ou non");
		}
	}
}
