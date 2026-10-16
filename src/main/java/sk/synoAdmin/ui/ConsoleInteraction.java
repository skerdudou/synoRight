package sk.synoAdmin.ui;

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
public final class ConsoleInteraction implements Interaction {

	private final Scanner stdin;

	public ConsoleInteraction() {
		// encodage de la console (CP850...) et non celui de la JVM (UTF-8), sinon les accents saisis sont corrompus
		this.stdin = new Scanner(System.in, stdinCharset());
	}

	/** Entrée simulée (tests). */
	public ConsoleInteraction(final InputStream input) {
		this.stdin = new Scanner(input);
	}

	public static Charset stdinCharset() {
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
	@Override
	public String promptLine(final String label) {
		String value;
		do {
			System.out.println(label);
			value = stdin.nextLine().trim();
		} while (value.isEmpty());

		return value;
	}

	/**
	 * Demande une valeur, Entrée seule conservant la valeur par défaut (si non nulle).
	 */
	@Override
	public String promptLine(final String label, final String defaultValue) {
		if (defaultValue == null || defaultValue.isEmpty()) {
			return promptLine(label);
		}
		System.out.println(label + " [" + defaultValue + "]");
		final String value = stdin.nextLine().trim();
		return value.isEmpty() ? defaultValue : value;
	}

	/**
	 * Fait choisir un ou plusieurs éléments d'une liste numérotée (saisie du type "1,3").
	 *
	 * @return les index (base 0) choisis, sans doublon, dans l'ordre de saisie
	 */
	@Override
	public List<Integer> promptChoices(final String label, final List<String> items) {
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
	@Override
	public String promptSecret(final String label) {
		String value;
		do {
			value = readSecret(label);
		} while (value.isEmpty());
		return value;
	}

	/**
	 * Demande une valeur sensible, Entrée seule renvoyant null (conserver la valeur actuelle).
	 */
	@Override
	public String promptSecretOrKeep(final String label) {
		final String value = readSecret(label);
		return value.isEmpty() ? null : value;
	}

	private String readSecret(final String label) {
		final Console console = System.console();
		if (console != null && console.isTerminal()) {
			final char[] value = console.readPassword(label + " ");
			return value == null ? "" : new String(value);
		}
		System.out.println(label);
		return stdin.nextLine().trim();
	}

	/**
	 * Pose une question fermée oui/non.
	 */
	@Override
	public boolean promptBool(final String label) {
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

	@Override
	public void info(final String message) {
		System.out.println(message);
	}

	@Override
	public void error(final String message, final Throwable detail) {
		System.err.println(message);
		if (detail != null) {
			detail.printStackTrace();
		}
	}

	@Override
	public boolean isGraphical() {
		return false;
	}
}
