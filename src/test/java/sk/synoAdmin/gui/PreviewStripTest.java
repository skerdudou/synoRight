package sk.synoAdmin.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import sk.synoAdmin.UserException;
import sk.synoAdmin.preview.PreviewImages.Preview;
import sk.synoAdmin.preview.PreviewImages.Source;
import sk.synoAdmin.preview.PreviewItem;

class PreviewStripTest {

	/** Exécuteur manuel : les tâches attendent l'appel de runAll(). */
	private static final class ManualExecutor implements java.util.concurrent.Executor {
		final Queue<Runnable> tasks = new ArrayDeque<>();

		@Override
		public void execute(final Runnable task) {
			tasks.add(task);
		}

		void runAll() {
			while (!tasks.isEmpty()) {
				tasks.remove().run();
			}
		}
	}

	/** Vue enregistrant les événements reçus. */
	private static final class RecordingView implements PreviewStrip.View {
		final List<String> events = new ArrayList<>();

		@Override
		public void message(final String key) {
			events.add("message " + key);
		}

		@Override
		public void start(final List<PreviewItem> items) {
			events.add("start " + items.size());
		}

		@Override
		public void tile(final int index, final Preview preview) {
			events.add("tile " + index + " " + preview.item().fileName());
		}
	}

	/** Dossiers de test : nom du dossier ⇒ noms de fichiers ; un dossier absent lève une erreur. */
	private static final class FakeBackend implements PreviewStrip.Backend {
		final Map<String, List<String>> folders = new HashMap<>();
		final Map<String, Integer> picks = new HashMap<>();
		Consumer<String> onPick = folder -> {
			// rien
		};
		Consumer<PreviewItem> onLoad = item -> {
			// rien
		};
		final List<String> largeLoads = new ArrayList<>();

		@Override
		public List<PreviewItem> pick(final String folder) {
			picks.merge(folder, 1, Integer::sum);
			onPick.accept(folder);
			final List<String> names = folders.get(folder);
			if (names == null) {
				throw new UserException("NAS unreachable");
			}
			return names.stream().map(n -> new PreviewItem(n, Path.of(folder, n), Optional.empty())).toList();
		}

		@Override
		public Preview load(final PreviewItem item) {
			onLoad.accept(item);
			return new Preview(item, Optional.empty(), Source.ICON, "");
		}

		@Override
		public Preview loadLarge(final PreviewItem item, final int maxSide) {
			largeLoads.add(item.fileName());
			return new Preview(item, Optional.empty(), Source.ICON, "");
		}
	}

	private final ManualExecutor worker = new ManualExecutor();
	private final RecordingView view = new RecordingView();
	private final FakeBackend backend = new FakeBackend();
	private final PreviewStrip.Loading loading = new PreviewStrip.Loading(backend, worker, Runnable::run, view);

	@Test
	void dossierCharge_casesDAttentePuisVignettes() {
		backend.folders.put("a", List.of("1.jpg", "2.jpg"));

		loading.show("a");
		worker.runAll();

		assertEquals(List.of("message gui.previews.loading", "start 2", "tile 0 1.jpg", "tile 1 2.jpg"), view.events);
	}

	@Test
	void generationDepassee_resultatIgnore() {
		backend.folders.put("a", List.of("1.jpg"));
		backend.folders.put("b", List.of("2.jpg"));
		// l'utilisateur change de ligne pendant le chargement de "a"
		backend.onPick = folder -> {
			if (folder.equals("a")) {
				loading.show("b");
			}
		};

		loading.show("a");
		worker.runAll();

		assertEquals(List.of("message gui.previews.loading", "message gui.previews.loading", "start 1", "tile 0 2.jpg"),
				view.events);

		// "a" abandonné en cours : pas en cache, rechargé au retour
		backend.onPick = folder -> {
			// rien
		};
		loading.show("a");
		worker.runAll();
		assertEquals(2, backend.picks.get("a"));
	}

	@Test
	void dossierDejaCharge_servi_depuisLeCache() {
		backend.folders.put("a", List.of("1.jpg"));
		backend.folders.put("b", List.of("2.jpg"));
		loading.show("a");
		worker.runAll();
		loading.show("b");
		worker.runAll();
		view.events.clear();

		loading.show("a");

		assertTrue(worker.tasks.isEmpty());
		assertEquals(List.of("start 1", "tile 0 1.jpg"), view.events);
		assertEquals(1, backend.picks.get("a"));
	}

	@Test
	void memeDossierRedemande_chargementPasRelance() {
		backend.folders.put("a", List.of("1.jpg"));

		loading.show("a");
		loading.show("a");
		worker.runAll();

		assertEquals(1, backend.picks.get("a"));
		assertEquals(List.of("message gui.previews.loading", "start 1", "tile 0 1.jpg"), view.events);
	}

	@Test
	void echec_apercuIndisponible_nonMisEnCache() {
		loading.show("absent");
		worker.runAll();

		assertEquals(List.of("message gui.previews.loading", "message gui.previews.unavailable"), view.events);

		loading.show("absent");
		worker.runAll();
		assertEquals(2, backend.picks.get("absent"));
	}

	@Test
	void dossierSansPhoto_messageVide() {
		backend.folders.put("vide", List.of());

		loading.show("vide");
		worker.runAll();

		assertEquals(List.of("message gui.previews.loading", "message gui.previews.empty"), view.events);
	}

	@Test
	void aucuneSelection_invitationEtChargementAbandonne() {
		backend.folders.put("a", List.of("1.jpg"));

		loading.show("a");
		loading.clear();
		worker.runAll();

		assertEquals(List.of("message gui.previews.loading", "message gui.previews.selectFolder"), view.events);
	}

	@Test
	void fermeture_plusAucunChargement() {
		backend.folders.put("a", List.of("1.jpg"));

		loading.dispose();
		loading.show("a");
		worker.runAll();

		assertTrue(view.events.isEmpty());
	}

	@Test
	void apercuAgrandi_demandeDepasseeIgnoree() {
		final PreviewItem item = new PreviewItem("1.jpg", Path.of("a", "1.jpg"), Optional.empty());
		final List<String> shown = new ArrayList<>();
		final boolean[] wanted = { true };

		loading.loadLarge(item, 800, () -> wanted[0], p -> shown.add(p.item().fileName()));
		wanted[0] = false;
		worker.runAll();
		assertTrue(shown.isEmpty());

		wanted[0] = true;
		loading.loadLarge(item, 800, () -> wanted[0], p -> shown.add(p.item().fileName()));
		worker.runAll();
		assertEquals(List.of("1.jpg"), shown);
	}

	@Test
	void apercuAgrandi_prioritaireSurLesVignettesEnAttente() {
		backend.folders.put("a", List.of("1.jpg", "2.jpg", "3.jpg"));
		final PreviewItem big = new PreviewItem("big.jpg", Path.of("a", "big.jpg"), Optional.empty());
		// clic sur une case pendant le chargement de la première vignette
		backend.onLoad = item -> {
			if (item.fileName().equals("1.jpg")) {
				loading.loadLarge(big, 800, () -> true, p -> view.events.add("large " + p.item().fileName()));
			}
		};

		loading.show("a");
		worker.runAll();

		assertEquals(List.of("message gui.previews.loading", "start 3", "tile 0 1.jpg", "large big.jpg", "tile 1 2.jpg",
				"tile 2 3.jpg"), view.events);
		assertEquals(List.of("big.jpg"), backend.largeLoads);
	}

	@Test
	void apercuAgrandi_nouvelleDemandeRemplaceCelleEnAttente() {
		final PreviewItem first = new PreviewItem("1.jpg", Path.of("a", "1.jpg"), Optional.empty());
		final PreviewItem second = new PreviewItem("2.jpg", Path.of("a", "2.jpg"), Optional.empty());
		final List<String> shown = new ArrayList<>();

		loading.loadLarge(first, 800, () -> true, p -> shown.add(p.item().fileName()));
		loading.loadLarge(second, 800, () -> true, p -> shown.add(p.item().fileName()));
		worker.runAll();

		assertEquals(List.of("2.jpg"), shown);
		assertEquals(List.of("2.jpg"), backend.largeLoads);
	}

	@Test
	void echec_nouvelEssaiDepuisLaMemeLigne() {
		loading.show("a");
		worker.runAll();
		backend.folders.put("a", List.of("1.jpg"));
		view.events.clear();

		loading.retry();
		worker.runAll();

		assertEquals(2, backend.picks.get("a"));
		assertEquals(List.of("message gui.previews.loading", "start 1", "tile 0 1.jpg"), view.events);
	}

	@Test
	void nouvelEssai_sansEchecEnCours_sansEffet() {
		backend.folders.put("a", List.of("1.jpg"));
		loading.show("a");
		worker.runAll();
		loading.show("absent");
		worker.runAll();
		loading.clear();
		view.events.clear();

		loading.retry();
		worker.runAll();

		assertEquals(1, backend.picks.get("a"));
		assertEquals(1, backend.picks.get("absent"));
		assertTrue(view.events.isEmpty());
	}
}
