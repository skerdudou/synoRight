package sk.synoAdmin.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
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

		final RightEngine engine = loadedEngine();
		engine.apply(engine.planPull());

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

		final RightEngine engine = loadedEngine();
		engine.apply(engine.planPush());

		assertEquals(List.of(
				"{\"id\":3,\"type\":\"group\",\"list\":[{\"folder_id\":1,\"action\":\"update\",\"role\":\"download\"},"
						+ "{\"folder_id\":2,\"action\":\"update\",\"role\":\"download\"}]}",
				"{\"id\":5,\"type\":\"group\",\"list\":[{\"folder_id\":1,\"action\":\"delete\"}]}"),
				dsm.permissionUpdates());
	}

	@Test
	void plan_neModifieRien() throws IOException {
		dsm.album(null, 1, "/a", 3).album(null, 3, "/c").album(1, 2, "/a/b");
		Files.createFile(rightFile("c", AMIS));

		final RightEngine engine = loadedEngine();
		final SyncPlan pull = engine.planPull();
		final SyncPlan push = engine.planPush();

		assertEquals(SyncPlan.Direction.PULL, pull.direction());
		assertEquals(2, pull.changeCount()); // famille ajouté sur /a, amis retiré de /c
		assertEquals(2, push.changeCount());
		assertEquals(3, push.folderCount());
		assertFalse(Files.exists(rightFile("a", FAMILLE)));
		assertTrue(Files.exists(rightFile("c", AMIS)));
		assertTrue(dsm.permissionUpdates().isEmpty());
	}

	@Test
	void plan_signaleDossiersNonIndexesEtInconnus() throws IOException {
		dsm.album(null, 1, "/a").album(null, 9, "/z", 3).album(1, 2, "/a/b");

		final SyncPlan plan = loadedEngine().planPush();

		assertEquals(List.of("/c"), plan.unindexedFolders());
		assertEquals(List.of("/z"), plan.unknownFolders());
	}

	@Test
	void folderRights_etatPhotosEtFichierParGroupe() throws IOException {
		dsm.album(null, 1, "/a", 3).album(1, 2, "/a/b");
		Files.createFile(rightFile("a", AMIS));

		final RightEngine engine = new RightEngine(pref);
		engine.login();
		final FolderRights rights = engine.folderRights(photos.resolve("a").toString());

		assertEquals("/a", rights.folderName());
		assertEquals(List.of(new FolderRights.GroupRight(FAMILLE, true, false), new FolderRights.GroupRight(AMIS, false, true)), rights.rights());
		assertTrue(rights.hasGap());
	}

	@Test
	void setFolderRights_ajouteEtRetireSelonLaCible() throws IOException {
		dsm.album(null, 1, "/a", 3).album(1, 2, "/a/b", 3);
		Files.createFile(rightFile("a/b", FAMILLE));

		final RightEngine engine = new RightEngine(pref);
		engine.login();
		final List<String> messages = engine.setFolderRights(photos.resolve("a/b").toString(), Set.of(AMIS));

		// groupes traités dans l'ordre de la configuration : famille (retrait) puis amis (ajout, parent compris)
		assertEquals(List.of(
				"{\"id\":3,\"type\":\"group\",\"list\":[{\"folder_id\":2,\"action\":\"delete\"}]}",
				"{\"id\":5,\"type\":\"group\",\"list\":[{\"folder_id\":2,\"action\":\"update\",\"role\":\"download\"},{\"folder_id\":1,\"action\":\"update\",\"role\":\"download\"}]}"),
				dsm.permissionUpdates());
		assertTrue(Files.exists(rightFile("a/b", AMIS)));
		assertTrue(Files.exists(rightFile("a", AMIS)));
		assertFalse(Files.exists(rightFile("a/b", FAMILLE)));
		assertEquals(2, messages.size());
	}

	@Test
	void setFolderRights_dejaConforme_rienAFaire() throws IOException {
		dsm.album(null, 1, "/a", 3);
		Files.createFile(rightFile("a", FAMILLE));

		final RightEngine engine = new RightEngine(pref);
		engine.login();

		assertTrue(engine.setFolderRights(photos.resolve("a").toString(), Set.of(FAMILLE)).isEmpty());
		assertTrue(dsm.permissionUpdates().isEmpty());
	}

	@Test
	void folderRights_plusieursDossiers_dansLOrdreDonne() throws IOException {
		dsm.album(null, 1, "/a", 3).album(null, 3, "/c").album(1, 2, "/a/b", 5);
		Files.createFile(rightFile("c", AMIS));

		final RightEngine engine = new RightEngine(pref);
		engine.login();
		final List<FolderRights> rights = engine.folderRights(List.of(
				photos.resolve("c").toString(), photos.resolve("a/b").toString(), photos.resolve("a").toString()));

		assertEquals(List.of("/c", "/a/b", "/a"), rights.stream().map(FolderRights::folderName).toList());
		assertEquals(new FolderRights.GroupRight(AMIS, false, true), rights.get(0).rights().get(1));
		assertEquals(new FolderRights.GroupRight(AMIS, true, false), rights.get(1).rights().get(1));
		assertEquals(new FolderRights.GroupRight(FAMILLE, true, false), rights.get(2).rights().get(0));
	}

	@Test
	void setFolderRights_plusieursDossiers_parentDAbord() {
		dsm.album(null, 1, "/a", 3).album(1, 2, "/a/b", 3);

		final RightEngine engine = new RightEngine(pref);
		engine.login();
		// sous-dossier donné en premier : traité après le parent, son ajout (parent compris) n'est pas défait
		final Map<String, Set<Group>> wanted = new LinkedHashMap<>();
		wanted.put(photos.resolve("a/b").toString(), Set.of(AMIS));
		wanted.put(photos.resolve("a").toString(), Set.of());
		engine.setFolderRights(wanted);

		assertEquals(List.of(
				"{\"id\":3,\"type\":\"group\",\"list\":[{\"folder_id\":1,\"action\":\"delete\"}]}",
				"{\"id\":3,\"type\":\"group\",\"list\":[{\"folder_id\":2,\"action\":\"delete\"}]}",
				"{\"id\":5,\"type\":\"group\",\"list\":[{\"folder_id\":2,\"action\":\"update\",\"role\":\"download\"},{\"folder_id\":1,\"action\":\"update\",\"role\":\"download\"}]}"),
				dsm.permissionUpdates());
		assertTrue(Files.exists(rightFile("a", AMIS)));
	}

	@Test
	void folderRights_dossierNonIndexe_ecarteEtSignale() {
		dsm.album(null, 1, "/a", 3);

		final RightEngine engine = new RightEngine(pref);
		engine.login();
		final List<String> errors = new ArrayList<>();
		final Map<String, FolderRights> rights = engine.folderRights(
				List.of(photos.resolve("c").toString(), photos.resolve("a").toString()), errors);

		assertEquals(List.of(photos.resolve("a").toString()), List.copyOf(rights.keySet()));
		assertEquals(1, errors.size());
		assertTrue(errors.getFirst().startsWith(photos.resolve("c").toString()), errors.getFirst());
	}

	@Test
	void checkFolders_ecarteLesDossiersHorsPerimetre() throws IOException {
		final Path troisiemeNiveau = Files.createDirectories(photos.resolve("a/b/c"));
		final RightEngine engine = new RightEngine(pref);
		final List<String> errors = new ArrayList<>();

		assertEquals(List.of(photos.resolve("a").toString()),
				engine.checkFolders(List.of(troisiemeNiveau.toString(), photos.resolve("a").toString()), errors));
		assertEquals(1, errors.size());
		assertThrows(UserException.class, () -> engine.checkFolders(List.of(troisiemeNiveau.toString()), new ArrayList<>()));
	}

	@Test
	void setFolderRights_dossierDisparu_signaleSansBloquer() {
		dsm.album(null, 1, "/a");

		final RightEngine engine = new RightEngine(pref);
		engine.login();
		final Map<String, Set<Group>> wanted = new LinkedHashMap<>();
		wanted.put(photos.resolve("disparu").toString(), Set.of(FAMILLE));
		wanted.put(photos.resolve("a").toString(), Set.of(FAMILLE));
		final List<String> messages = engine.setFolderRights(wanted);

		assertEquals(2, messages.size());
		assertTrue(Files.exists(rightFile("a", FAMILLE)));
	}

	@Test
	void setFolderRights_dossierEnErreur_lesAutresSontTraites() {
		// /c présent sur le partage mais pas (encore) indexé
		dsm.album(null, 1, "/a");

		final RightEngine engine = new RightEngine(pref);
		engine.login();
		final Map<String, Set<Group>> wanted = new LinkedHashMap<>();
		wanted.put(photos.resolve("c").toString(), Set.of(FAMILLE));
		wanted.put(photos.resolve("a").toString(), Set.of(FAMILLE));
		final List<String> messages = engine.setFolderRights(wanted);

		assertEquals(2, messages.size());
		assertTrue(messages.get(0).startsWith(photos.resolve("c").toString()), messages.get(0));
		assertTrue(Files.exists(rightFile("a", FAMILLE)));
	}

	@Test
	void folderId_idSynologyDuDossier() {
		dsm.album(null, 1, "/a").album(1, 2, "/a/b");

		final RightEngine engine = new RightEngine(pref);
		engine.login();

		assertEquals(2, engine.folderId(photos.resolve("a/b").toString()));
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void checkFolder_casseDifferente_accepte() {
		final String autreCasse = photos.resolve("a").toString().toUpperCase();

		assertEquals("a", new RightEngine(pref).checkFolder(autreCasse).toString().toLowerCase());
	}

	@Test
	void dossierNonIndexe_ignoreSansBloquer() throws IOException {
		// /c absent de Photos (pas encore indexé), /z absent du partage
		dsm.album(null, 1, "/a").album(null, 9, "/z", 3).album(1, 2, "/a/b");
		Files.createFile(rightFile("c", FAMILLE));

		final RightEngine engine = loadedEngine();
		engine.apply(engine.planPush());

		// rien à pousser : le seul écart concerne /c, ignoré
		assertTrue(dsm.permissionUpdates().isEmpty());
	}

	@Test
	void grant_ajouteSurLeDossierEtSonParent() {
		dsm.album(null, 1, "/a").album(1, 2, "/a/b");

		final RightEngine engine = new RightEngine(pref);
		engine.login();
		final String message = engine.addRightToFolder(photos.resolve("a/b").toString(), AMIS);

		assertTrue(message.contains("amis"));

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

		assertTrue(e.getMessage().contains("first 2 folder levels"));
	}

	@Test
	void checkFolder_horsDuDossierPhotos_refuse() {
		assertThrows(UserException.class, () -> new RightEngine(pref).checkFolder(tempDir.toString()));
	}
}
