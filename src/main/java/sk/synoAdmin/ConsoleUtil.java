package sk.synoAdmin;

import java.io.Console;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

/**
 * Saisies utilisateur sur la console.
 */
public final class ConsoleUtil {

	// encodage de la console (CP850...) et non celui de la JVM (UTF-8), sinon les accents saisis sont corrompus
	private static Scanner STDIN = new Scanner(System.in, stdinCharset());

	private ConsoleUtil() {
		// utility
	}

	static Charset stdinCharset() {
		final String encoding = System.getProperty("stdin.encoding");
		if (encoding != null && Charset.isSupported(encoding)) {
			return Charset.forName(encoding);
		}
		final Console console = System.console();
		return console != null ? console.charset() : Charset.defaultCharset();
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
	 * Demande une valeur, Entrée seule conservant la valeur par défaut (si non nulle).
	 */
	public static String promptLine(final String label, final String defaultValue) {
		if (defaultValue == null || defaultValue.isEmpty()) {
			return promptLine(label);
		}
		System.out.println(label + " [" + defaultValue + "]");
		final String value = STDIN.nextLine().trim();
		return value.isEmpty() ? defaultValue : value;
	}

	/**
	 * Fait choisir un ou plusieurs éléments d'une liste numérotée (saisie du type "1,3").
	 *
	 * @return les index (base 0) choisis, sans doublon, dans l'ordre de saisie
	 */
	public static List<Integer> promptChoices(final String label, final List<String> items) {
		for (int i = 0; i < items.size(); i++) {
			System.out.println("  " + (i + 1) + ") " + items.get(i));
		}
		while (true) {
			final String rep = promptLine(label + " (numéros séparés par des virgules)");
			final Set<Integer> choices = new LinkedHashSet<>();
			boolean valid = true;
			for (final String part : rep.split("[,; ]+")) {
				try {
					final int choice = Integer.parseInt(part.trim());
					if (choice < 1 || choice > items.size()) {
						valid = false;
					}
					choices.add(choice - 1);
				} catch (final NumberFormatException e) {
					valid = false;
				}
			}
			if (valid && !choices.isEmpty()) {
				return new ArrayList<>(choices);
			}
			System.out.println("Saisir des numéros entre 1 et " + items.size());
		}
	}

	/**
	 * Demande une valeur sensible, saisie masquée quand une vraie console est disponible.
	 */
	public static String promptSecret(final String label) {
		String value;
		do {
			value = readSecret(label);
		} while (value.isEmpty());
		return value;
	}

	/**
	 * Demande une valeur sensible, Entrée seule renvoyant null (conserver la valeur actuelle).
	 */
	public static String promptSecretOrKeep(final String label) {
		final String value = readSecret(label);
		return value.isEmpty() ? null : value;
	}

	private static String readSecret(final String label) {
		final Console console = System.console();
		if (console != null && console.isTerminal()) {
			final char[] value = console.readPassword(label + " ");
			return value == null ? "" : new String(value);
		}
		System.out.println(label);
		return STDIN.nextLine().trim();
	}

	/**
	 * Remplace l'entrée standard lue (tests).
	 */
	public static void setInput(final InputStream input) {
		STDIN = new Scanner(input);
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
