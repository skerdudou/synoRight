package sk.synoAdmin.i18n;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Langues supportées par l'application (l'anglais est la référence et le repli).
 */
public enum Language {

	EN("en"), FR("fr"), DE("de"), ES("es"), IT("it");

	/** Propriété système imposant la langue (tests, dépannage) ; prioritaire sur la configuration. */
	public static final String SYSTEM_PROPERTY = "synoRight.language";

	private final String code;

	Language(final String code) {
		this.code = code;
	}

	public String code() {
		return code;
	}

	public Locale locale() {
		return Locale.of(code);
	}

	public static Optional<Language> fromCode(final String code) {
		if (code == null) {
			return Optional.empty();
		}
		final String trimmed = code.trim();
		return Arrays.stream(values()).filter(l -> l.code.equalsIgnoreCase(trimmed)).findFirst();
	}

	/**
	 * Langue à utiliser : propriété système, puis réglage (code valide), puis langue d'affichage
	 * du système si elle est supportée, sinon l'anglais.
	 *
	 * @param setting valeur du réglage de configuration ("auto", un code de langue ou null)
	 */
	public static Language resolve(final String setting) {
		return fromCode(System.getProperty(SYSTEM_PROPERTY))
				.or(() -> fromCode(setting))
				.or(() -> fromCode(Locale.getDefault(Locale.Category.DISPLAY).getLanguage()))
				.orElse(EN);
	}
}
