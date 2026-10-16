package sk.synoAdmin.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class CommandLinesTest {
	@Test
	void quote_argumentSimpleInchange() {
		assertEquals("rights", CommandLines.quote("rights"));
	}

	@Test
	void quote_espacesEtGuillemets() {
		assertEquals("\"Famille Dupont\"", CommandLines.quote("Famille Dupont"));
		assertEquals("\"dit \\\"x\\\"\"", CommandLines.quote("dit \"x\""));
	}

	@Test
	void quote_backslashFinalDouble() {
		assertEquals("\"D:\\mes conf\\\\\"", CommandLines.quote("D:\\mes conf\\"));
	}

	@Test
	void quote_cheminExplorerToujoursEntreGuillemets() {
		assertEquals("\"%V\"", CommandLines.quote("%V"));
	}

	@Test
	void join_espaceEntreArguments() {
		assertEquals("-c \"D:\\a b\" rights \"%V\"", CommandLines.join(List.of("-c", "D:\\a b", "rights", "%V")));
	}
}
