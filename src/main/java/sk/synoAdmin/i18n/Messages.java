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

	/** Bundle de la langue courante (pour picocli) ; les clés absentes ne sont pas complétées par l'anglais. */
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
		for (int i = 0; i < args.length; i++) {
			text = text.replace("{" + i + "}", String.valueOf(args[i]));
		}
		return text;
	}

	private static String lookup(final ResourceBundle source, final String key) {
		try {
			return source.getString(key);
		} catch (final MissingResourceException e) {
			return null;
		}
	}
}
