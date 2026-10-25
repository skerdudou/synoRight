package sk.synoAdmin.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sk.synoAdmin.engine.FolderRights;
import sk.synoAdmin.engine.Group;

class FolderRightsTableModelTest {
	private static final Group FAMILLE = new Group("3", "famille");
	private static final Group AMIS = new Group("5", "amis");

	private static FolderRightsTableModel model() {
		return new FolderRightsTableModel("P:\\a", new FolderRights("/a", List.of(
				new FolderRights.GroupRight(FAMILLE, true, true),
				new FolderRights.GroupRight(AMIS, false, true))));
	}

	@Test
	void caseInitiale_etatSynologyPhotos() {
		final FolderRightsTableModel model = model();

		assertEquals(Boolean.TRUE, model.getValueAt(0, 0));
		assertEquals(Boolean.FALSE, model.getValueAt(1, 0));
		assertEquals("famille", model.getValueAt(0, 1));
		assertEquals("yes", model.getValueAt(0, 2));
		assertEquals("yes", model.getValueAt(1, 3));
		assertFalse(model.isModified());
		assertTrue(model.hasGap(1));
	}

	@Test
	void cocher_modifieLaCible() {
		final FolderRightsTableModel model = model();
		model.setValueAt(Boolean.TRUE, 1, 0);

		assertEquals(Set.of(FAMILLE, AMIS), model.wantedGroups());
		assertEquals(Map.of("P:\\a", Set.of(FAMILLE, AMIS)), model.wanted());
		assertTrue(model.isModified());
		assertTrue(model.isCellEditable(1, 0));
		assertFalse(model.isCellEditable(1, 1));
	}

	@Test
	void ecartSansChangementDeCase_resteAAppliquer() {
		// amis : fichier présent mais pas dans Photos ; case décochée = retirer aussi le fichier
		assertTrue(model().hasPendingChanges());
	}
}
