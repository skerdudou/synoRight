package sk.synoAdmin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MainTest {

	@Test
	void ancienneSyntaxe_detectee() {
		assertTrue(Main.isLegacySyntax(new String[] { "-a", "FULL_DB_TO_FS", "-c", "x" }));
		assertTrue(Main.isLegacySyntax(new String[] { "--action=INIT" }));
		assertFalse(Main.isLegacySyntax(new String[] { "-c", "x", "pull" }));
	}
}
