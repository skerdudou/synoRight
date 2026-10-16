package sk.synoAdmin.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MessagesTest {

	private static final String LANGUAGE_PROPERTY = "synoRight.language";
	private static final Pattern PARAM = Pattern.compile("\\{(\\d+)}");

	private String savedProperty;
	private Locale savedLocale;

	@BeforeEach
	void save() {
		savedProperty = System.getProperty(LANGUAGE_PROPERTY);
		savedLocale = Locale.getDefault(Locale.Category.DISPLAY);
	}

	@AfterEach
	void restore() {
		if (savedProperty == null) {
			System.clearProperty(LANGUAGE_PROPERTY);
		} else {
			System.setProperty(LANGUAGE_PROPERTY, savedProperty);
		}
		Locale.setDefault(Locale.Category.DISPLAY, savedLocale);
		Messages.setLanguage(Language.EN);
	}

	@Test
	void substitution() {
		Messages.setLanguage(Language.EN);
		assertEquals("A x B 2", Messages.get("test.params", "x", 2));
	}

	@Test
	void substitution_uneSeulePasseEtSansArguments() {
		Messages.setLanguage(Language.EN);
		assertEquals("A {1} B 2", Messages.get("test.params", "{1}", 2));
		assertEquals("A {0} B {1}", Messages.get("test.params"));
		assertEquals("A {0} B {1}", Messages.get("test.params", (Object[]) null));
	}

	@Test
	void apostropheConservee() {
		Messages.setLanguage(Language.FR);
		assertEquals("l'album 'X'", Messages.get("test.apostrophe", "X"));
	}

	@Test
	void cleAbsente_repliAnglaisPuisCle() {
		Messages.setLanguage(Language.FR);
		assertEquals("English only", Messages.get("test.englishOnly"));
		assertEquals("!cle.inexistante!", Messages.get("cle.inexistante"));
	}

	@Test
	void resolve_codeExplicite() {
		System.clearProperty(LANGUAGE_PROPERTY);
		assertEquals(Language.FR, Language.resolve("fr"));
	}

	@Test
	void resolve_automatique() {
		System.clearProperty(LANGUAGE_PROPERTY);
		Locale.setDefault(Locale.Category.DISPLAY, Locale.GERMANY);
		assertEquals(Language.DE, Language.resolve("auto"));
		assertEquals(Language.DE, Language.resolve(null));
		assertEquals(Language.DE, Language.resolve("xx"));
		Locale.setDefault(Locale.Category.DISPLAY, Locale.JAPAN);
		assertEquals(Language.EN, Language.resolve("auto"));
		assertEquals(Language.EN, Language.resolve(null));
	}

	@Test
	void resolve_proprieteSystemePrioritaire() {
		System.setProperty(LANGUAGE_PROPERTY, "de");
		assertEquals(Language.DE, Language.resolve("fr"));
	}

	@Test
	void fromCode() {
		assertEquals(Language.IT, Language.fromCode("it").orElseThrow());
		assertTrue(Language.fromCode("xx").isEmpty());
		assertTrue(Language.fromCode(null).isEmpty());
	}

	@Test
	void memesClesEtParametres() throws IOException {
		final Map<String, Set<String>> english = load("messages.properties");
		for (final String file : new String[] { "messages_fr.properties", "messages_de.properties", "messages_es.properties", "messages_it.properties" }) {
			final Map<String, Set<String>> other = load(file);
			final boolean onlyTests = other.keySet().stream().allMatch(k -> k.startsWith("test."));
			if (onlyTests && !file.equals("messages_fr.properties")) {
				continue;
			}
			final Set<String> expected = new TreeSet<>(english.keySet());
			expected.remove("test.englishOnly");
			assertEquals(expected, other.keySet(), "clés de " + file);
			for (final String key : expected) {
				assertEquals(english.get(key), other.get(key), "paramètres de " + key + " dans " + file);
			}
		}
	}

	// charge directement le fichier (pas de repli du ResourceBundle) : clé -> ensemble des {n}
	private static Map<String, Set<String>> load(final String file) throws IOException {
		final Properties prop = new Properties();
		try (InputStream in = MessagesTest.class.getResourceAsStream("/i18n/" + file);
				Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			prop.load(reader);
		}
		final Map<String, Set<String>> result = new TreeMap<>();
		for (final String key : prop.stringPropertyNames()) {
			final Set<String> params = new TreeSet<>();
			final Matcher matcher = PARAM.matcher(prop.getProperty(key));
			while (matcher.find()) {
				params.add(matcher.group(1));
			}
			result.put(key, params);
		}
		return result;
	}
}
