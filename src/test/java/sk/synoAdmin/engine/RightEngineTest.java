package sk.synoAdmin.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sk.synoAdmin.FakeDsm;
import sk.synoAdmin.UserException;
import sk.synoAdmin.prefs.Preferences;

/**
 * Moteur de synchro sur un vrai dossier temporaire et un faux DSM.
 * Arborescence : /a (id 1), /a/b (id 2), /c (id 3) ; groupes famille (3) et amis (5).
 */
class RightEngineTest {

	static {
		// ne pas toucher la clé réelle de l'utilisateur quand les tests tournent hors de maven
		if (System.getProperty("synoRight.keyFile") == null) {
			System.setProperty("synoRight.keyFile", System.getProperty("java.io.tmpdir") + "/synoRight-test.key");
		}
	}

	private static final Group FAMILLE = new Group("3", "famille");
	private static final Group AMIS = new Group("5", "amis");

	@TempDir
	Path tempDir;

	private Path photos;
	private FakeDsm dsm;
	private Preferences pref;

	@BeforeEach
	void setUp() throws IOException {
		photos = Files.createDirectories(tempDir.resolve("photos"));
		Files.createDirectories(photos.resolve("a").resolve("b"));
		Files.createDirectories(photos.resolve("c"));
		dsm = new FakeDsm();
		pref = new Preferences(tempDir.resolve("conf"), photos.toString(), dsm.url(), "admin", "pwd", List.of(FAMILLE, AMIS), false);
	}

	@AfterEach
	void tearDown() {
		dsm.close();
	}

	private RightEngine loadedEngine() {
		final RightEngine engine = new RightEngine(pref);
		engine.getFullState();
		return engine;
	}

	private Path rightFile(final String folder, final Group group) {
		return photos.resolve(folder).resolve(group.getFileName());
	}

	@Test
	void pull_creeEtSupprimeLesFichiersDApresPhotos() throws IOException {
		dsm.album(null, 1, "/a", 3).album(null, 3, "/c").album(1, 2, "/a/b", 3, 5);
		Files.createFile(rightFile("c", FAMILLE)); // plus autorisé dans Photos

		loadedEngine().dbToFs(true, false);

		assertTrue(Files.exists(rightFile("a", FAMILLE)));
		assertTrue(Files.exists(rightFile("a/b", FAMILLE)));
		assertTrue(Files.exists(rightFile("a/b", AMIS)));
		assertFalse(Files.exists(rightFile("a", AMIS)));
		assertFalse(Files.exists(rightFile("c", FAMILLE)));
		assertTrue(dsm.permissionUpdates().isEmpty());
	}

	@Test
	void push_appliqueLesFichiersDansPhotos() throws IOException {
		dsm.album(null, 1, "/a", 5).album(null, 3, "/c").album(1, 2, "/a/b");
		Files.createFile(rightFile("a", FAMILLE));
		Files.createFile(rightFile("a/b", FAMILLE));

		loadedEngine().fsToDb(true, false);

		assertEquals(List.of(
				"{\"id\":3,\"type\":\"group\",\"list\":[{\"folder_id\":1,\"action\":\"update\",\"role\":\"download\"},"
						+ "{\"folder_id\":2,\"action\":\"update\",\"role\":\"download\"}]}",
				"{\"id\":5,\"type\":\"group\",\"list\":[{\"folder_id\":1,\"action\":\"delete\"}]}"),
				dsm.permissionUpdates());
	}

	@Test
	void simulation_rienNEstModifie() throws IOException {
		dsm.album(null, 1, "/a", 3).album(null, 3, "/c").album(1, 2, "/a/b");
		Files.createFile(rightFile("c", AMIS));

		final RightEngine engine = loadedEngine();
		engine.dbToFs(true, true);
		engine.fsToDb(true, true);

		assertFalse(Files.exists(rightFile("a", FAMILLE)));
		assertTrue(Files.exists(rightFile("c", AMIS)));
		assertTrue(dsm.permissionUpdates().isEmpty());
	}

	@Test
	void dossierNonIndexe_ignoreSansBloquer() throws IOException {
		// /c absent de Photos (pas encore indexé), /z absent du partage
		dsm.album(null, 1, "/a").album(null, 9, "/z", 3).album(1, 2, "/a/b");
		Files.createFile(rightFile("c", FAMILLE));

		loadedEngine().fsToDb(true, false);

		// rien à pousser : le seul écart concerne /c, ignoré
		assertTrue(dsm.permissionUpdates().isEmpty());
	}

	@Test
	void grant_ajouteSurLeDossierEtSonParent() {
		dsm.album(null, 1, "/a").album(1, 2, "/a/b");

		final RightEngine engine = new RightEngine(pref);
		engine.login();
		engine.addRightToFolder(photos.resolve("a/b").toString(), AMIS);

		assertEquals(List.of("{\"id\":5,\"type\":\"group\",\"list\":["
				+ "{\"folder_id\":2,\"action\":\"update\",\"role\":\"download\"},"
				+ "{\"folder_id\":1,\"action\":\"update\",\"role\":\"download\"}]}"), dsm.permissionUpdates());
		assertTrue(Files.exists(rightFile("a/b", AMIS)));
		assertTrue(Files.exists(rightFile("a", AMIS)));
	}

	@Test
	void checkFolder_auDelaDeDeuxNiveaux_refuse() throws IOException {
		final Path deep = Files.createDirectories(photos.resolve("a/b/c"));

		final UserException e = assertThrows(UserException.class, () -> new RightEngine(pref).checkFolder(deep.toString()));

		assertTrue(e.getMessage().contains("2 premiers niveaux"));
	}

	@Test
	void checkFolder_horsDuDossierPhotos_refuse() {
		assertThrows(UserException.class, () -> new RightEngine(pref).checkFolder(tempDir.toString()));
	}
}
