package sk.synoAdmin.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;

import sk.synoAdmin.engine.Group;

class ModernMenuInstallerTest {

	private static final List<MenuDefinition.MenuItem> ITEMS = MenuDefinition.build(
			List.of(MenuEntry.RIGHTS, MenuEntry.STATUS), List.of(new Group("3", "famille")));

	@Test
	void registre_lanceurDossierPhotosEtEntrees() {
		final String content = ModernMenuInstaller.buildRegFileContent(ITEMS, "C:\\Apps\\synoRight\\synoRightGui.exe", Paths.get("D:\\conf dir"), "P:\\");

		assertTrue(content.startsWith("Windows Registry Editor Version 5.00\r\n"));
		assertTrue(content.contains("[-HKEY_CURRENT_USER\\Software\\synoRight\\Menu]"));
		assertTrue(content.contains("[HKEY_CURRENT_USER\\Software\\synoRight\\Menu]\r\n\"Launcher\"=\"C:\\\\Apps\\\\synoRight\\\\synoRightGui.exe\"\r\n"
				+ "\"ConfigDir\"=\"D:\\\\conf dir\"\r\n\"PhotoFolder\"=\"P:\\\\\""));
		assertTrue(content.contains("[HKEY_CURRENT_USER\\Software\\synoRight\\Menu\\Entries\\01]\r\n\"Title\"=\"Droits du dossier…\"\r\n"
				+ "\"Args\"=\"-c \\\"D:\\\\conf dir\\\" rights \\\"%V\\\"\"\r\n\"SeparatorBefore\"=dword:00000000"));
		assertTrue(content.contains("[HKEY_CURRENT_USER\\Software\\synoRight\\Menu\\Entries\\02]\r\n\"Title\"=\"État de la synchronisation…\"\r\n"
				+ "\"Args\"=\"-c \\\"D:\\\\conf dir\\\" sync\"\r\n\"SeparatorBefore\"=dword:00000001"));
	}

	@Test
	void psQuote_apostrophesDoublees() {
		assertEquals("'C:\\l''album'", ModernMenuInstaller.psQuote("C:\\l'album"));
	}
}
