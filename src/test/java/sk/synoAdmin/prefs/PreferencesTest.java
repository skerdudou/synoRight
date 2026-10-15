package sk.synoAdmin.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sk.synoAdmin.UserException;
import sk.synoAdmin.engine.Group;

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
		return Preferences.fromFile(tempDir);
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

		assertTrue(e.getMessage().contains("init"));
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
	void sessionUpdate_persisteChiffreeDansUnFichierSepare() throws IOException {
		writeConfig("folder=f\nurl=u\nuser=admin\ngroups=3:famille\n");

		readPrefs().getSession().update("abc123", "tok", "did1");

		final Preferences relu = readPrefs();
		assertEquals("abc123", relu.getSession().getCookieSession());
		assertEquals("did1", relu.getSession().getCookieDid());
		// rien dans la configuration, chiffré dans le fichier de session
		assertTrue(Files.readString(tempDir.resolve(Preferences.CONFIG_FILENAME)).indexOf("cookieSession") < 0);
		assertTrue(lineValue(Session.FILENAME, "cookieSession").startsWith("v2\\:"));
	}

	@Test
	void sessionUpdate_sansDeviceId_conserveLePrecedent() throws IOException {
		writeConfig("folder=f\nurl=u\nuser=admin\ngroups=3:famille\n");
		readPrefs().getSession().update("s1", "t1", "did1");

		readPrefs().getSession().update("s2", "t2", null);

		assertEquals("did1", readPrefs().getSession().getCookieDid());
		assertEquals("s2", readPrefs().getSession().getCookieSession());
	}

	@Test
	void sessionClear_supprimeLeFichier() throws IOException {
		writeConfig("folder=f\nurl=u\nuser=admin\ngroups=3:famille\n");
		readPrefs().getSession().update("s1", "t1", "did1");

		readPrefs().getSession().clear();

		assertFalse(Files.exists(tempDir.resolve(Session.FILENAME)));
		assertNull(readPrefs().getSession().getCookieDid());
	}

	@Test
	void fromFile_sessionDansLaConfig_deplaceeDansLeFichierDeSession() throws IOException {
		writeConfig("folder=f\nurl=u\nuser=admin\ngroups=3:famille\ncookieSession=sid123\nsynoToken="
				+ CryptoUtils.encrypt("tok456") + "\n");

		final Preferences pref = readPrefs();
		assertEquals("sid123", pref.getSession().getCookieSession());
		assertEquals("tok456", pref.getSession().getSynoToken());

		// la configuration ne contient plus la session, toujours lisible ensuite
		final String config = Files.readString(tempDir.resolve(Preferences.CONFIG_FILENAME));
		assertFalse(config.contains("cookieSession"));
		assertFalse(config.contains("synoToken"));
		assertTrue(config.contains("user=admin"));
		final Preferences relu = readPrefs();
		assertEquals("sid123", relu.getSession().getCookieSession());
		assertEquals("tok456", relu.getSession().getSynoToken());
	}

	@Test
	void verbose_luDansLaConfigEtEcritParSave() throws IOException {
		writeConfig("folder=f\nurl=u\nuser=admin\ngroups=3:famille\nverbose=true\n");

		final Preferences pref = readPrefs();
		assertTrue(pref.isVerbose());

		pref.setGroups(List.of(new Group("5", "amis")));
		pref.save();
		final Preferences relu = readPrefs();
		assertTrue(relu.isVerbose());
		assertEquals("amis", relu.getGroups().get(0).getName());
	}

	@Test
	void verbose_absent_faux() throws IOException {
		writeConfig("folder=f\nurl=u\nuser=admin\ngroups=3:famille\n");

		assertFalse(readPrefs().isVerbose());
	}

	@Test
	void setPassword_nullOublieLeMotDePasse() throws IOException {
		writeConfig("folder=f\nurl=u\nuser=admin\npassword=" + CryptoUtils.encrypt("s3cret") + "\ngroups=3:famille\n");

		final Preferences pref = readPrefs();
		pref.setPassword(null);
		pref.save();

		assertNull(readPrefs().getPassword());
	}

	@Test
	void setGroups_vide_exception() throws IOException {
		writeConfig("folder=f\nurl=u\nuser=admin\ngroups=3:famille\n");

		assertThrows(UserException.class, () -> readPrefs().setGroups(List.of()));
	}

	@Test
	void fromFile_accentsEditesALaMainEnUtf8() throws IOException {
		writeConfig("folder=\\\\\\\\NAS\\\\photo\\\\Été\nurl=u\nuser=admin\ngroups=3:Famille élargie\n");

		final Preferences pref = readPrefs();

		assertEquals("\\\\NAS\\photo\\Été", pref.getNasFolder());
		assertEquals("Famille élargie", pref.getGroups().get(0).getName());
	}

	@Test
	void fromFile_accentsAncienFichierIso88591() throws IOException {
		Files.write(tempDir.resolve(Preferences.CONFIG_FILENAME),
				"folder=f\nurl=u\nuser=admin\ngroups=3:Famille élargie\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

		assertEquals("Famille élargie", readPrefs().getGroups().get(0).getName());
	}

	@Test
	void save_ecritLesAccentsEnUtf8Lisible() throws IOException {
		writeConfig("folder=f\nurl=u\nuser=admin\ngroups=3:famille\n");
		final Preferences pref = readPrefs();

		pref.setGroups(List.of(new Group("3", "Famille élargie")));
		pref.save();

		assertTrue(Files.readString(tempDir.resolve(Preferences.CONFIG_FILENAME)).contains("Famille élargie"));
		assertEquals("Famille élargie", readPrefs().getGroups().get(0).getName());
	}

	@Test
	void resolveConfigDir_explicite() {
		assertEquals(tempDir.toAbsolutePath().normalize(), Preferences.resolveConfigDir(tempDir));
	}

	private String lineValue(final String fileName, final String key) throws IOException {
		return Files.readAllLines(tempDir.resolve(fileName)).stream()
				.filter(l -> l.startsWith(key + "="))
				.findAny().orElseThrow()
				.substring(key.length() + 1);
	}
}
