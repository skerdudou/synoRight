package sk.synoAdmin.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PropertiesFilesTest {
	@TempDir
	Path tmp;

	@Test
	void storeOverExistingFile_leavesCompleteFileAndNoTemporaryFile() throws IOException {
		final Path file = tmp.resolve("session.properties");
		Files.writeString(file, "old=value\nlonger=" + "x".repeat(10_000) + "\n");
		final Properties prop = new Properties();
		prop.setProperty("cookieSession", "abc");
		prop.setProperty("synoToken", "dé");

		PropertiesFiles.store(file, prop, "comment");

		assertEquals(prop, PropertiesFiles.load(file));
		try (Stream<Path> files = Files.list(tmp)) {
			assertEquals(List.of(file), files.toList());
		}
	}

	@Test
	void storeInMissingDirectory_createsIt() throws IOException {
		final Path file = tmp.resolve("sub").resolve("config.properties");
		final Properties prop = new Properties();
		prop.setProperty("k", "v");

		PropertiesFiles.store(file, prop, "comment");

		assertEquals(prop, PropertiesFiles.load(file));
	}
}
