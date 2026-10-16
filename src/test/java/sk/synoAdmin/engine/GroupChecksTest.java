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
				List.of(new SynoGroup(3, "Famille"), new SynoGroup(7, "Smith family")),
				List.of(new Group("3", "famille"), new Group("5", "amis"), new Group("7", "Smith")));

		assertEquals(List.of("Warning: the group 'amis' (id 5) no longer exists on the NAS."), warnings);
	}

	@Test
	void invalidShortName_caracteresInterditsVideOuDoublon() {
		final List<Group> groups = List.of(new Group("3", "Smith"), new Group("5", "Jones"));

		assertNull(GroupChecks.invalidShortName("Smith family", groups, "7"));
		assertNull(GroupChecks.invalidShortName("Smith", groups, "3"));
		assertEquals("empty name", GroupChecks.invalidShortName("  ", groups, "7"));
		assertEquals("forbidden character ':'", GroupChecks.invalidShortName("a:b", groups, "7"));
		assertEquals("forbidden character ';'", GroupChecks.invalidShortName("a;b", groups, "7"));
		assertEquals("forbidden character '/'", GroupChecks.invalidShortName("a/b", groups, "7"));
		assertEquals("already used by another group", GroupChecks.invalidShortName("smith", groups, "7"));
	}
}
