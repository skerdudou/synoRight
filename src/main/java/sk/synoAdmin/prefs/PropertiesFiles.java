package sk.synoAdmin.prefs;

import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Lecture/écriture des fichiers .properties en UTF-8, pour qu'un fichier édité à la
 * main avec des accents (noms de groupes, chemins) soit relu correctement.
 */
final class PropertiesFiles {

	private PropertiesFiles() {
		// utility
	}

	/**
	 * Lit un fichier en UTF-8 ; à défaut (fichier ISO-8859-1 édité à la main avec
	 * l'ancien format), en ISO-8859-1.
	 */
	static Properties load(final Path file) throws IOException {
		final byte[] bytes = Files.readAllBytes(file);
		String content;
		try {
			content = StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes))
					.toString();
		} catch (final CharacterCodingException e) {
			content = new String(bytes, StandardCharsets.ISO_8859_1);
		}

		final Properties prop = new Properties();
		prop.load(new StringReader(content));
		return prop;
	}

	/**
	 * Ecrit le fichier en UTF-8 (accents lisibles, pas d'échappements \\uXXXX).
	 */
	static void store(final Path file, final Properties prop, final String comment) throws IOException {
		Files.createDirectories(file.toAbsolutePath().getParent());
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			prop.store(writer, comment);
		}
	}
}
