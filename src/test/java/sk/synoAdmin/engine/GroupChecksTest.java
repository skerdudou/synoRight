package sk.synoAdmin.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import sk.synoAdmin.synology.SynoGroup;

class GroupChecksTest {

	@Test
	void warnings_groupeAbsentOuRenomme() {
		final List<String> warnings = GroupChecks.warnings(
				List.of(new SynoGroup(3, "Famille"), new SynoGroup(7, "copains")),
				List.of(new Group("3", "famille"), new Group("5", "amis"), new Group("7", "amis2")));

		assertEquals(List.of(
				"Attention : le groupe 'amis' (id 5) n'existe plus sur le NAS.",
				"Attention : le groupe d'id 7 s'appelle 'copains' sur le NAS, mais 'amis2' dans la configuration."), warnings);
	}
}
