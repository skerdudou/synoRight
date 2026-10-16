package sk.synoAdmin.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;

import sk.synoAdmin.engine.Group;

class ClassicMenuInstallerTest {

	private static final List<String> JAR_LAUNCHER = List.of("C:\\Java\\bin\\javaw.exe", "-jar", "D:\\apps\\synoRight.jar", "gui");
	private static final Path CONFIG = Paths.get("D:\\apps");

	private static String buildContent(final List<MenuEntry> entries) {
		return ClassicMenuInstaller.buildRegFileContent(
				MenuDefinition.build(entries, List.of(new Group("3", "Famille Dupont"))), "\\\\NAS\\photo", JAR_LAUNCHER, CONFIG);
	}

	@Test
	void entete_formatRegVersion5() {
		assertTrue(buildContent(List.of(MenuEntry.RIGHTS)).startsWith("Windows Registry Editor Version 5.00\r\n"));
	}

	@Test
	void clesRacines_sousHkcuAvecSousMenu() {
		final String content = buildContent(List.of(MenuEntry.RIGHTS));

		assertTrue(content.contains("[HKEY_CURRENT_USER\\Software\\Classes\\Directory\\shell\\01SynoRight]"));
		assertTrue(content.contains("[HKEY_CURRENT_USER\\Software\\Classes\\Directory\\Background\\shell\\01SynoRight]"));
		assertTrue(content.contains("\"ExtendedSubCommandsKey\"=\"Directory\\\\ContextMenus\\\\SynoRight\""));
	}

	@Test
	void clesRacines_iconeDuLanceur() {
		final String content = buildContent(List.of(MenuEntry.RIGHTS));

		// sur la clé du dossier et sur celle du fond de dossier
		final String icon = "\"Icon\"=\"C:\\\\Java\\\\bin\\\\javaw.exe,0\"";
		assertTrue(content.indexOf(icon) >= 0 && content.indexOf(icon) != content.lastIndexOf(icon));
	}

	@Test
	void appliesTo_restreintAuDossierPhotos() {
		// \\NAS\photo\* avec les backslashs doublés au format .reg
		assertTrue(buildContent(List.of(MenuEntry.RIGHTS)).contains("\"AppliesTo\"=\"\\\\\\\\NAS\\\\photo\\\\*\""));
	}

	@Test
	void entree_commandeVersLanceurGuiAvecConfig() {
		final String content = buildContent(List.of(MenuEntry.RIGHTS));

		assertTrue(content.contains("\\shell\\01rights]"));
		assertTrue(content.contains("\"MUIVerb\"=\"Folder permissions…\""));
		// "C:\Java\bin\javaw.exe" -jar "D:\apps\synoRight.jar" gui -c "D:\apps" rights "%V" (échappé .reg)
		assertTrue(content.contains("@=\"C:\\\\Java\\\\bin\\\\javaw.exe -jar D:\\\\apps\\\\synoRight.jar gui -c D:\\\\apps rights \\\"%V\\\"\""));
	}

	@Test
	void titreRacine_enAnglais() {
		assertTrue(buildContent(List.of(MenuEntry.RIGHTS)).contains("\"MUIVerb\"=\"Synology Photos permissions\""));
	}

	@Test
	void separateur_avantLaPremiereEntreeDUnBloc() {
		final String content = buildContent(List.of(MenuEntry.RIGHTS, MenuEntry.STATUS));

		assertTrue(content.contains("\\shell\\02status]\r\n\"MUIVerb\"=\"Synchronization status…\"\r\n\"CommandFlags\"=dword:00000020"));
	}

	@Test
	void groupeAvecEspaces_echappeDansLeReg() {
		final String content = buildContent(List.of(MenuEntry.GROUPS));

		assertTrue(content.contains("\"MUIVerb\"=\"Add Famille Dupont\""));
		assertTrue(content.contains(" grant \\\"Famille Dupont\\\" \\\"%V\\\"\""));
	}

	@Test
	void entreesNonChoisies_absentes() {
		final String content = buildContent(List.of(MenuEntry.RIGHTS));

		assertFalse(content.contains("sync"));
		assertFalse(content.contains("grant"));
	}

	@Test
	void desinstallation_supprimeLesTroisCles() {
		final List<List<String>> commands = ClassicMenuInstaller.buildUninstallCommands();

		assertEquals(3, commands.size());
		assertTrue(commands.stream().allMatch(c -> c.get(0).equals("reg") && c.get(1).equals("delete") && c.contains("/f")));
	}
}
