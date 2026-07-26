package sk.synoAdmin.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import sk.synoAdmin.engine.DeltaResolver.GrpDelta;

class DeltaResolverTest {

	private final Group famille = new Group("3", "famille");
	private final Group amis = new Group("5", "amis");

	private static Folder folder(final String name) {
		return new Folder(new File(name), null);
	}

	@Test
	void fsToDb_groupePresentSurFsSeulement_estAAjouter() {
		final Folder vacances = folder("vacances");
		vacances.addFsGroup(famille);

		final Map<Group, GrpDelta> deltas = DeltaResolver.resolveDeltasFsToDb(List.of(vacances));

		assertEquals(1, deltas.size());
		assertEquals(List.of(vacances), deltas.get(famille).getToAdd());
		assertTrue(deltas.get(famille).getToRemove().isEmpty());
	}

	@Test
	void fsToDb_groupePresentEnDbSeulement_estASupprimer() {
		final Folder vacances = folder("vacances");
		vacances.addDbGroup(famille);

		final Map<Group, GrpDelta> deltas = DeltaResolver.resolveDeltasFsToDb(List.of(vacances));

		assertEquals(1, deltas.size());
		assertEquals(List.of(vacances), deltas.get(famille).getToRemove());
		assertTrue(deltas.get(famille).getToAdd().isEmpty());
	}

	@Test
	void fsToDb_dejaSynchronise_aucunDelta() {
		final Folder vacances = folder("vacances");
		vacances.addFsGroup(famille);
		vacances.addDbGroup(famille);

		final Map<Group, GrpDelta> deltas = DeltaResolver.resolveDeltasFsToDb(List.of(vacances));

		assertTrue(deltas.isEmpty());
	}

	@Test
	void dbToFs_groupePresentEnDbSeulement_estAAjouter() {
		final Folder vacances = folder("vacances");
		vacances.addDbGroup(famille);

		final Map<Group, GrpDelta> deltas = DeltaResolver.resolveDeltasDbToFs(List.of(vacances));

		assertEquals(1, deltas.size());
		assertEquals(List.of(vacances), deltas.get(famille).getToAdd());
		assertTrue(deltas.get(famille).getToRemove().isEmpty());
	}

	@Test
	void fsToDb_plusieursDossiersEtGroupes_deltasRegroupesParGroupe() {
		// noel : famille sur FS et rien en DB -> à ajouter pour famille
		final Folder noel = folder("noel");
		noel.addFsGroup(famille);

		// plage : amis en DB mais plus sur FS -> à supprimer pour amis
		final Folder plage = folder("plage");
		plage.addDbGroup(amis);

		// mariage : famille sur FS, amis en DB -> à ajouter pour famille, à supprimer pour amis
		final Folder mariage = folder("mariage");
		mariage.addFsGroup(famille);
		mariage.addDbGroup(amis);

		final Map<Group, GrpDelta> deltas = DeltaResolver.resolveDeltasFsToDb(List.of(noel, plage, mariage));

		assertEquals(2, deltas.size());
		assertEquals(List.of(noel, mariage), deltas.get(famille).getToAdd());
		assertTrue(deltas.get(famille).getToRemove().isEmpty());
		assertEquals(List.of(plage, mariage), deltas.get(amis).getToRemove());
		assertTrue(deltas.get(amis).getToAdd().isEmpty());
	}
}
