package sk.synoAdmin.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Ligne de commande Windows (règles de CommandLineToArgvW) : guillemets et backslashs.
 */
public final class CommandLines {

	private CommandLines() {
		// utility
	}

	/** Met l'argument entre guillemets si nécessaire ; le chemin Explorer (%V) l'est toujours. */
	public static String quote(final String arg) {
		final boolean needsQuotes = arg.isEmpty() || arg.equals(MenuDefinition.PATH)
				|| arg.chars().anyMatch(c -> c == ' ' || c == '\t' || c == '"');
		if (!needsQuotes) {
			return arg;
		}
		final StringBuilder quoted = new StringBuilder("\"");
		int backslashes = 0;
		for (final char c : arg.toCharArray()) {
			if (c == '\\') {
				backslashes++;
				continue;
			}
			if (c == '"') {
				// backslashs précédant un guillemet doublés, guillemet échappé
				quoted.append("\\".repeat(backslashes * 2 + 1)).append('"');
			} else {
				quoted.append("\\".repeat(backslashes)).append(c);
			}
			backslashes = 0;
		}
		// backslashs finaux doublés : ils précèdent le guillemet fermant
		quoted.append("\\".repeat(backslashes * 2)).append('"');
		return quoted.toString();
	}

	/** Assemble les arguments en une ligne de commande. */
	public static String join(final List<String> args) {
		return args.stream().map(CommandLines::quote).collect(Collectors.joining(" "));
	}

	/** Découpe une ligne de commande en arguments (inverse de join ; la forme "" dans des guillemets n'est pas gérée). */
	public static List<String> split(final String line) {
		final List<String> args = new ArrayList<>();
		final StringBuilder current = new StringBuilder();
		boolean inQuotes = false;
		boolean inArg = false;
		int backslashes = 0;
		for (final char c : line.toCharArray()) {
			if (c == '\\') {
				backslashes++;
				inArg = true;
				continue;
			}
			if (c == '"') {
				// 2n backslashs : n backslashs et guillemet délimiteur ; 2n+1 : n backslashs et guillemet littéral
				current.append("\\".repeat(backslashes / 2));
				if (backslashes % 2 == 1) {
					current.append('"');
				} else {
					inQuotes = !inQuotes;
				}
				backslashes = 0;
				inArg = true;
				continue;
			}
			current.append("\\".repeat(backslashes));
			backslashes = 0;
			if ((c == ' ' || c == '\t') && !inQuotes) {
				if (inArg) {
					args.add(current.toString());
					current.setLength(0);
					inArg = false;
				}
			} else {
				current.append(c);
				inArg = true;
			}
		}
		current.append("\\".repeat(backslashes));
		if (inArg) {
			args.add(current.toString());
		}
		return args;
	}
}
