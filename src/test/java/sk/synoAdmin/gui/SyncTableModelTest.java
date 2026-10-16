package sk.synoAdmin.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import sk.synoAdmin.engine.DeltaResolver;
import sk.synoAdmin.engine.Folder;
import sk.synoAdmin.engine.Group;
import sk.synoAdmin.engine.SyncPlan;

class SyncTableModelTest {
	private static final Group FAMILLE = new Group("3", "famille");
	private static final Group AMIS = new Group("5", "amis");

	@Test
	void ecarts_uneLigneParDossierEtGroupe() {
		final DeltaResolver.GrpDelta famille = new DeltaResolver.GrpDelta();
		famille.getToAdd().add(new Folder(new File("b"), null));
		final DeltaResolver.GrpDelta amis = new DeltaResolver.GrpDelta();
		amis.getToRemove().add(new Folder(new File("a"), null));
		final Map<Group, DeltaResolver.GrpDelta> deltas = new LinkedHashMap<>();
		deltas.put(FAMILLE, famille);
		deltas.put(AMIS, amis);

		final SyncTableModel model = new SyncTableModel(new SyncPlan(SyncPlan.Direction.PUSH, deltas, 2, List.of(), List.of()));

		assertEquals(2, model.getRowCount());
		assertEquals(List.of("/a", "amis", "oui", "non"), row(model, 0));
		assertEquals(List.of("/b", "famille", "non", "oui"), row(model, 1));
	}

	private static List<Object> row(final SyncTableModel model, final int row) {
		return IntStream.range(0, model.getColumnCount()).mapToObj(c -> model.getValueAt(row, c)).toList();
	}
}
