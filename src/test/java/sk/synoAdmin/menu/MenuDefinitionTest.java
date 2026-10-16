package sk.synoAdmin.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sk.synoAdmin.engine.Group;
import sk.synoAdmin.i18n.Language;
import sk.synoAdmin.i18n.Messages;

class MenuDefinitionTest {
	private static final List<Group> GROUPS = List.of(new Group("3", "famille"), new Group("5", "Amis & \"co\""));

	@Test
	void ordreEtSeparateurs() {
		final List<MenuDefinition.MenuItem> items = MenuDefinition.build(List.of(MenuEntry.CONFIG, MenuEntry.PUSH, MenuEntry.RIGHTS, MenuEntry.STATUS), GROUPS);

		assertEquals(List.of("01rights", "02status", "03push", "04config"), items.stream().map(MenuDefinition.MenuItem::key).toList());
		assertEquals(List.of(false, true, false, true), items.stream().map(MenuDefinition.MenuItem::separatorBefore).toList());
		assertEquals(List.of("rights", "%V"), items.get(0).args());
		assertEquals(List.of("sync"), items.get(1).args());
		assertEquals(List.of("sync", "--push"), items.get(2).args());
		assertEquals(List.of("config"), items.get(3).args());
		assertEquals("Folder permissions…", items.get(0).title());
	}

	@Test
	void groupes_ajouterEtRetirerParGroupe() {
		final List<MenuDefinition.MenuItem> items = MenuDefinition.build(List.of(MenuEntry.GROUPS), GROUPS);

		assertEquals(List.of("01add3", "02del3", "03add5", "04del5"), items.stream().map(MenuDefinition.MenuItem::key).toList());
		assertEquals("Add famille", items.get(0).title());
		assertEquals("Remove famille", items.get(1).title());
		assertEquals(List.of("grant", "famille", "%V"), items.get(0).args());
		assertEquals(List.of("revoke", "Amis & \"co\"", "%V"), items.get(3).args());
	}

	@Test
	void titres_enFrancais() {
		Messages.setLanguage(Language.FR);
		try {
			final List<MenuDefinition.MenuItem> items = MenuDefinition.build(List.of(MenuEntry.RIGHTS, MenuEntry.GROUPS), GROUPS);

			assertEquals("Droits du dossier…", items.get(0).title());
			assertEquals("Ajouter famille", items.get(1).title());
			assertEquals("Retirer famille", items.get(2).title());
			assertEquals("Droits Synology Photos", MenuDefinition.rootTitle());
		} finally {
			Messages.setLanguage(Language.EN);
		}
	}

	@Test
	void commandLine_groupeAvecEspacesEtGuillemets() {
		final MenuDefinition.MenuItem item = MenuDefinition.build(List.of(MenuEntry.GROUPS), GROUPS).get(2);

		assertEquals("grant \"Amis & \\\"co\\\"\" \"%V\"", CommandLines.join(item.args()));
	}

	@Test
	void open_pullEtSeparateurs() {
		final List<MenuDefinition.MenuItem> items = MenuDefinition.build(List.of(MenuEntry.OPEN, MenuEntry.PULL), GROUPS);

		assertEquals(List.of("open", "%V"), items.get(0).args());
		assertEquals(List.of("sync", "--pull"), items.get(1).args());
		assertTrue(items.get(1).separatorBefore());
	}
}
