package sk.synoAdmin.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sk.synoAdmin.engine.Group;
import sk.synoAdmin.menu.MenuEntry;
import sk.synoAdmin.menu.MenuTarget;
import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.prefs.Session;
import sk.synoAdmin.synology.ConnectionCheck;

class ConfigFormTest {

	static {
		if (System.getProperty("synoRight.keyFile") == null) {
			System.setProperty("synoRight.keyFile", System.getProperty("java.io.tmpdir") + "/synoRight-test.key");
		}
	}

	private static List<String> messages(final ConfigForm form) {
		return form.validate().stream().map(ConfigForm.Problem::message).toList();
	}

	private static List<ConfigForm.Section> sections(final ConfigForm form) {
		return form.validate().stream().map(ConfigForm.Problem::section).toList();
	}

	@Test
	void langue_modifiee_etatModifieEtEnregistree(@TempDir final Path dir) {
		final Preferences pref = new Preferences(dir, dir.toString(), "https://nas", "admin", "pwd", List.of(new Group("3", "famille")), false);
		final ConfigForm form = ConfigForm.from(pref);
		assertEquals("auto", form.language);
		assertFalse(form.isDirty());

		form.language = "fr";

		assertTrue(form.isDirty());
		assertTrue(form.validate().isEmpty());
		assertEquals("fr", form.toPreferences(dir, false).getLanguageSetting());

		form.language = "auto";
		assertFalse(form.isDirty());
	}

	@Test
	void langue_reprendLaConfiguration(@TempDir final Path dir) {
		final Preferences pref = new Preferences(dir, dir.toString(), "https://nas", "admin", "pwd", List.of(new Group("3", "famille")), false);
		pref.setLanguageSetting("de");

		final ConfigForm form = ConfigForm.from(pref);

		assertEquals("de", form.language);
		assertFalse(form.isDirty());
		assertEquals("auto", ConfigForm.from(null).language);
	}

	@Test
	void nouvelleConfiguration_connexionEtGroupesObligatoires(@TempDir final Path dir) throws IOException {
		final ConfigForm form = ConfigForm.from(null);
		form.url = "nas:5001";
		form.user = "admin";
		form.folder = dir.toString();

		assertEquals(List.of("Test the connection to the NAS.", "Select at least one group."), messages(form));
		assertEquals(List.of(ConfigForm.Section.CONNECTION, ConfigForm.Section.GROUPS), sections(form));
	}

	@Test
	void configurationExistante_sansChangementDeConnexion_pasDeTestRequis(@TempDir final Path dir) {
		final Preferences pref = new Preferences(dir, dir.toString(), "https://nas", "admin", "pwd", List.of(new Group("3", "famille")), false);
		final ConfigForm form = ConfigForm.from(pref);

		assertTrue(form.validate().isEmpty());
		assertFalse(form.isDirty());

		form.user = "autre";
		assertTrue(form.isDirty());
		assertTrue(form.connectionChanged());
		assertEquals(List.of("Test the connection to the NAS."), messages(form));
		assertEquals(List.of(ConfigForm.Section.CONNECTION), sections(form));
	}

	@Test
	void dossierInaccessible_signale(@TempDir final Path dir) {
		final Preferences pref = new Preferences(dir, dir.resolve("absent").toString(), "https://nas", "admin", "pwd", List.of(new Group("3", "famille")), false);

		final ConfigForm form = ConfigForm.from(pref);

		assertEquals(List.of("Photo folder not found or not accessible."), messages(form));
		assertEquals(List.of(ConfigForm.Section.FOLDER), sections(form));
	}

	@Test
	void toPreferences_urlNormaliseeEtMenu(@TempDir final Path dir) {
		final Preferences pref = new Preferences(dir, dir.toString(), "https://nas", "admin", "pwd", List.of(new Group("3", "famille")), false);
		final ConfigForm form = ConfigForm.from(pref);
		form.modernMenuEntries = List.of(MenuEntry.RIGHTS);
		form.classicMenuEntries = List.of(MenuEntry.PUSH, MenuEntry.CONFIG);
		form.menuTarget = MenuTarget.CLASSIC;
		form.storePassword = false;

		final Preferences saved = form.toPreferences(dir, false);

		assertEquals("https://nas", saved.getUrl());
		assertNull(saved.getPassword());
		assertEquals(List.of(MenuEntry.RIGHTS), saved.getMenuEntries(true));
		assertEquals(List.of(MenuEntry.PUSH, MenuEntry.CONFIG), saved.getMenuEntries(false));
		assertEquals(MenuTarget.CLASSIC, saved.getMenuTarget());
	}

	@Test
	void groupesDansUnAutreOrdre_pasDeModification(@TempDir final Path dir) {
		final Preferences pref = new Preferences(dir, dir.toString(), "https://nas", "admin", "pwd",
				List.of(new Group("5", "zoé"), new Group("3", "famille")), false);
		final ConfigForm form = ConfigForm.from(pref);

		form.groups = List.of(new Group("3", "famille"), new Group("5", "zoé"));

		assertFalse(form.isDirty());
	}

	@Test
	void nomCourt_invalideSignale(@TempDir final Path dir) {
		final Preferences pref = new Preferences(dir, dir.toString(), "https://nas", "admin", "pwd",
				List.of(new Group("65536", "Kerdudou")), false);
		final ConfigForm form = ConfigForm.from(pref);

		form.groups = List.of(new Group("65536", "Kerdudou"), new Group("65537", "kerdudou"));

		assertEquals(List.of("Short name of the group with id 65537 is invalid (\"kerdudou\"): already used by another group."),
				messages(form).stream().filter(p -> p.contains("65537")).toList());
		assertTrue(form.validate().stream().allMatch(p -> p.section() == ConfigForm.Section.GROUPS));
	}

	@Test
	void nomCourtModifie_noteSurLesFichiersExistants(@TempDir final Path dir) {
		final Preferences pref = new Preferences(dir, dir.toString(), "https://nas", "admin", "pwd",
				List.of(new Group("65536", "Kerdudou"), new Group("65537", "Godmez")), false);
		final ConfigForm form = ConfigForm.from(pref);
		assertTrue(form.renamedGroupNotes().isEmpty());

		form.groups = List.of(new Group("65536", "Famille Kerdudou"), new Group("65537", "Godmez"));

		assertEquals(1, form.renamedGroupNotes().size());
		assertTrue(form.renamedGroupNotes().get(0).contains("@Kerdudou.synoRight"));
	}

	@Test
	void motDePasseVide_conserveLActuel(@TempDir final Path dir) {
		final Preferences pref = new Preferences(dir, dir.toString(), "https://nas", "admin", "pwd", List.of(new Group("3", "famille")), false);
		final ConfigForm form = ConfigForm.from(pref);

		form.setPasswordInput("autre");
		assertTrue(form.connectionChanged());
		form.setPasswordInput("");
		assertEquals("pwd", form.password);
		assertTrue(form.passwordFromConfig());
		assertFalse(form.isDirty());
	}

	@Test
	void testDeConnexion_valideLesValeursTestees(@TempDir final Path dir) {
		final ConfigForm form = ConfigForm.from(null);
		form.url = "nas";
		form.user = "admin";
		form.setPasswordInput("pwd");
		final Session work = Session.load(dir).transientCopy(false);

		form.connected(new ConnectionCheck.Result(null, work, "corrigé", "AB:CD"));

		assertTrue(form.connectionVerified());
		assertTrue(form.hasConnected());
		assertSame(work, form.workSession());
		assertEquals("AB:CD", form.toPreferences(dir, false).getCertFingerprint());
		assertEquals("corrigé", form.toPreferences(dir, false).getPassword());

		form.user = "autre";
		assertFalse(form.connectionVerified());
		assertNull(form.workSession());
	}
}
