package sk.synoAdmin.i18n;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Textes de l'application dans la langue courante, avec repli sur l'anglais puis sur la clé.
 */
public final class Messages {

	private static final String BASE_NAME = "i18n.messages";
	private static final ResourceBundle.Control CONTROL =
			ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

	private static volatile Language language = Language.EN;
	private static volatile ResourceBundle bundle = load(Language.EN);
	private static final ResourceBundle ENGLISH = load(Language.EN);

	private Messages() {
	}

	private static ResourceBundle load(final Language lang) {
		// anglais : fichier racine (messages.properties) ; sans repli sur la langue du système
		final Locale locale = lang == Language.EN ? Locale.ROOT : lang.locale();
		return ResourceBundle.getBundle(BASE_NAME, locale, CONTROL);
	}

	public static void setLanguage(final Language newLanguage) {
		language = newLanguage;
		bundle = load(newLanguage);
	}

	public static Language language() {
		return language;
	}

	/**
	 * Bundle de la langue courante (pour picocli). Sans repli sur la langue du système, mais le fichier
	 * racine (anglais) reste son parent : une clé absente de la langue est retrouvée en anglais.
	 */
	public static ResourceBundle bundle() {
		return bundle;
	}

	/**
	 * Texte de la clé dans la langue courante (sinon en anglais, sinon "!clé!"), les {n} étant
	 * remplacés par les arguments.
	 */
	public static String get(final String key, final Object... args) {
		String text = lookup(bundle, key);
		if (text == null) {
			text = lookup(ENGLISH, key);
		}
		if (text == null) {
			return "!" + key + "!";
		}
		return substitute(text, args);
	}

	// une seule passe : un argument contenant "{1}" n'est pas re-substitué
	private static String substitute(final String text, final Object[] args) {
		if (args == null || args.length == 0) {
			return text;
		}
		final StringBuilder result = new StringBuilder(text.length() + 16);
		int i = 0;
		while (i < text.length()) {
			final char c = text.charAt(i);
			if (c == '{') {
				final int end = text.indexOf('}', i);
				if (end > i + 1 && isNumber(text, i + 1, end)) {
					final int index = Integer.parseInt(text.substring(i + 1, end));
					if (index < args.length) {
						result.append(args[index]);
						i = end + 1;
						continue;
					}
				}
			}
			result.append(c);
			i++;
		}
		return result.toString();
	}

	private static boolean isNumber(final String text, final int from, final int to) {
		if (to - from > 9) {
			return false;
		}
		for (int i = from; i < to; i++) {
			if (!Character.isDigit(text.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static String lookup(final ResourceBundle source, final String key) {
		try {
			return source.getString(key);
		} catch (final MissingResourceException e) {
			return null;
		}
	}
}
