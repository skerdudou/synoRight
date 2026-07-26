package sk.synoAdmin.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sk.synoAdmin.UserException;

class PreferencesTest {

	static {
		// ne pas toucher la clé réelle de l'utilisateur quand les tests tournent hors de maven
		if (System.getProperty("synoRight.keyFile") == null) {
			System.setProperty("synoRight.keyFile", System.getProperty("java.io.tmpdir") + "/synoRight-test.key");
		}
	}

	@TempDir
	Path tempDir;

	private void writeConfig(final String content) throws IOException {
		Files.writeString(tempDir.resolve(Preferences.CONFIG_FILENAME), content);
	}

	private Preferences readPrefs() {
		return Preferences.fromFile(tempDir.toString());
	}

	@Test
	void fromFile_configurationComplete() throws IOException {
		writeConfig("folder=D\\:/photos\nurl=https\\://nas\\:5001\nuser=admin\ngroups=3:famille;5:amis\n");

		final Preferences pref = readPrefs();

		assertEquals("D:/photos", pref.getNasFolder());
		assertEquals("https://nas:5001", pref.getUrl());
		assertEquals("admin", pref.getUser());
		assertNull(pref.getPassword());
		assertEquals(2, pref.getGroups().size());
		assertEquals("famille", pref.getGroups().get(0).getName());
	}

	@Test
	void fromFile_passwordDechiffre() throws IOException {
		writeConfig("folder=f\nurl=u\nuser=admin\npassword=" + CryptoUtils.encrypt("s3cret") + "\ngroups=3:famille\n");

		assertEquals("s3cret", readPrefs().getPassword());
	}

	@Test
	void fromFile_ancienFormatDePassword_migreVersAesGcm() throws Exception {
		writeConfig("folder=f\nurl=u\nuser=admin\npassword=" + CryptoUtilsTest.legacyEncrypt("s3cret") + "\ngroups=3:famille\n");

		assertEquals("s3cret", readPrefs().getPassword());

		// le fichier a été réécrit au nouveau format, toujours déchiffrable
		final String migrated = Files.readAllLines(tempDir.resolve(Preferences.CONFIG_FILENAME)).stream()
				.filter(l -> l.startsWith("password="))
				.findAny().orElseThrow()
				.substring("password=".length());
		assertTrue(migrated.startsWith("v2\\:"));
		assertEquals("s3cret", readPrefs().getPassword());
	}

	@Test
	void fromFile_fichierAbsent_messageProposantInit() {
		final UserException e = assertThrows(UserException.class, this::readPrefs);

		assertTrue(e.getMessage().contains("INIT"));
	}

	@Test
	void fromFile_proprieteObligatoireManquante_messageExplicite() throws IOException {
		writeConfig("folder=f\nuser=admin\ngroups=3:famille\n"); // pas d'url

		final UserException e = assertThrows(UserException.class, this::readPrefs);

		assertTrue(e.getMessage().contains("url"));
	}

	@Test
	void fromFile_formatDeGroupeIncorrect_exception() throws IOException {
		writeConfig("folder=f\nurl=u\nuser=admin\ngroups=3famille\n");

		assertThrows(UserException.class, this::readPrefs);
	}

	@Test
	void getGroupByName_insensibleALaCasse() throws IOException {
		writeConfig("folder=f\nurl=u\nuser=admin\ngroups=3:famille;5:amis\n");

		assertEquals("3", readPrefs().getGroupByName("FAMILLE").getId());
	}

	@Test
	void getGroupByName_groupeInconnu_exception() throws IOException {
		writeConfig("folder=f\nurl=u\nuser=admin\ngroups=3:famille\n");

		assertThrows(UserException.class, () -> readPrefs().getGroupByName("inconnu"));
	}

	@Test
	void getGroupById() throws IOException {
		writeConfig("folder=f\nurl=u\nuser=admin\ngroups=3:famille;5:amis\n");

		final Preferences pref = readPrefs();

		assertTrue(pref.getGroupById("5").isPresent());
		assertTrue(pref.getGroupById("9").isEmpty());
	}

	@Test
	void setCookieSession_persisteDansLeFichier() throws IOException {
		writeConfig("folder=f\nurl=u\nuser=admin\ngroups=3:famille\n");

		readPrefs().setCookieSession("abc123");

		final Preferences relu = readPrefs();
		assertEquals("abc123", relu.getCookieSession());
		// les autres propriétés ne sont pas perdues
		assertEquals("admin", relu.getUser());
	}
}
