package sk.synoAdmin.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import sk.synoAdmin.synology.SynoGroup;

class GroupChecksTest {

	@Test
	void warnings_groupeAbsentSeulement() {
		// un nom court différent du nom DSM (id 7) est voulu : pas d'avertissement
		final List<String> warnings = GroupChecks.warnings(
				List.of(new SynoGroup(3, "Famille"), new SynoGroup(7, "Famille Kerdudou")),
				List.of(new Group("3", "famille"), new Group("5", "amis"), new Group("7", "Kerdudou")));

		assertEquals(List.of("Attention : le groupe 'amis' (id 5) n'existe plus sur le NAS."), warnings);
	}

	@Test
	void invalidShortName_caracteresInterditsVideOuDoublon() {
		final List<Group> groups = List.of(new Group("3", "Kerdudou"), new Group("5", "Godmez"));

		assertNull(GroupChecks.invalidShortName("Famille Kerdudou", groups, "7"));
		assertNull(GroupChecks.invalidShortName("Kerdudou", groups, "3"));
		assertEquals("nom vide", GroupChecks.invalidShortName("  ", groups, "7"));
		assertEquals("caractère interdit « : »", GroupChecks.invalidShortName("a:b", groups, "7"));
		assertEquals("caractère interdit « ; »", GroupChecks.invalidShortName("a;b", groups, "7"));
		assertEquals("caractère interdit « / »", GroupChecks.invalidShortName("a/b", groups, "7"));
		assertEquals("déjà utilisé par un autre groupe", GroupChecks.invalidShortName("kerdudou", groups, "7"));
	}
}
