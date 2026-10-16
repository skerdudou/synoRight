package sk.synoAdmin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RightsCommandTest {
	@Test
	void parseSelection_numerosZeroEtInvalides() {
		assertEquals(Optional.of(Set.of(0, 2)), RightsCommand.parseSelection("1, 3", 3));
		assertEquals(Optional.of(Set.of()), RightsCommand.parseSelection("0", 3));
		assertEquals(Optional.empty(), RightsCommand.parseSelection("4", 3));
		assertEquals(Optional.empty(), RightsCommand.parseSelection("a", 3));
	}
}
