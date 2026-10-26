package sk.synoAdmin.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sk.synoAdmin.engine.FolderRights;
import sk.synoAdmin.engine.Group;

class FoldersRightsTableModelTest {
	private static final Group FAMILLE = new Group("3", "famille");
	private static final Group AMIS = new Group("5", "amis");

	private static FoldersRightsTableModel model() {
		return new FoldersRightsTableModel(List.of("P:\\a", "P:\\a\\b", "P:\\c"), List.of(
				new FolderRights("/a", List.of(new FolderRights.GroupRight(FAMILLE, true, true), new FolderRights.GroupRight(AMIS, false, false))),
				new FolderRights("/a/b", List.of(new FolderRights.GroupRight(FAMILLE, true, true), new FolderRights.GroupRight(AMIS, false, false))),
				new FolderRights("/c", List.of(new FolderRights.GroupRight(FAMILLE, false, false), new FolderRights.GroupRight(AMIS, false, true)))));
	}

	@Test
	void uneLigneParDossier_uneColonneParGroupe() {
		final FoldersRightsTableModel model = model();

		assertEquals(3, model.getRowCount());
		assertEquals(3, model.getColumnCount());
		assertEquals("famille", model.getColumnName(1));
		assertEquals("/a/b", model.getValueAt(1, 0));
		assertEquals(Boolean.TRUE, model.getValueAt(1, 1));
		assertEquals(Boolean.FALSE, model.getValueAt(1, 2));
		assertFalse(model.isCellEditable(0, 0));
		assertTrue(model.isCellEditable(0, 2));
	}

	@Test
	void ecart_signaleSurLaCelluleEtLeDossier() {
		final FoldersRightsTableModel model = model();

		assertTrue(model.hasGap(2, 2));
		assertFalse(model.hasGap(2, 1));
		assertTrue(model.hasGap(2, 0));
		assertFalse(model.hasGap(0, 0));
		// écart à corriger sans changement de case
		assertTrue(model.hasPendingChanges());
		assertEquals("Synology Photos: no — file: yes", model.toolTip(2, 2));
	}

	@Test
	void cocherUnSousDossier_cocheAussiLeParent() {
		final FoldersRightsTableModel model = model();
		model.setValueAt(Boolean.TRUE, 1, 2);

		assertEquals(Set.of(FAMILLE, AMIS), model.wanted().get("P:\\a\\b"));
		assertEquals(Set.of(FAMILLE, AMIS), model.wanted().get("P:\\a"));
		assertEquals(Set.of(), model.wanted().get("P:\\c"));
	}

	@Test
	void decocherLeParent_decocheAussiSesSousDossiers() {
		final FoldersRightsTableModel model = model();
		model.setValueAt(Boolean.FALSE, 0, 1);

		assertEquals(Set.of(), model.wanted().get("P:\\a"));
		assertEquals(Set.of(), model.wanted().get("P:\\a\\b"));
	}

	@Test
	void decocherUnSousDossier_laisseLeParent() {
		final FoldersRightsTableModel model = model();
		model.setValueAt(Boolean.FALSE, 1, 1);

		assertEquals(Set.of(FAMILLE), model.wanted().get("P:\\a"));
		assertEquals(Set.of(), model.wanted().get("P:\\a\\b"));
	}

	@Test
	void clicSurLEnTete_cochePuisDecocheTouteLaColonne() {
		final FoldersRightsTableModel model = model();

		model.toggleColumn(1);
		assertTrue(model.wanted().values().stream().allMatch(groups -> groups.contains(FAMILLE)));

		model.toggleColumn(1);
		assertTrue(model.wanted().values().stream().noneMatch(groups -> groups.contains(FAMILLE)));
	}

	@Test
	void folderAt_cheminDeLaLigne() {
		assertEquals("P:\\a\\b", model().folderAt(1));
	}
}
