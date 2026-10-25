package sk.synoAdmin.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import sk.synoAdmin.engine.Group;

class ModernMenuInstallerTest {

	private static final List<MenuDefinition.MenuItem> ITEMS = MenuDefinition.build(
			List.of(MenuEntry.RIGHTS, MenuEntry.STATUS), List.of(new Group("3", "famille")));

	@Test
	void registre_lanceurDossierPhotosEtEntrees() {
		final String content = ModernMenuInstaller.buildRegFileContent(ITEMS, "C:\\Apps\\synoRight\\synoRightGui.exe", Paths.get("D:\\conf dir"), "P:\\", false);

		assertTrue(content.startsWith("Windows Registry Editor Version 5.00\r\n"));
		assertTrue(content.contains("[-HKEY_CURRENT_USER\\Software\\synoRight\\Menu]"));
		assertTrue(content.contains("[HKEY_CURRENT_USER\\Software\\synoRight\\Menu]\r\n\"Launcher\"=\"C:\\\\Apps\\\\synoRight\\\\synoRightGui.exe\"\r\n"
				+ "\"ConfigDir\"=\"D:\\\\conf dir\"\r\n\"PhotoFolder\"=\"P:\\\\\"\r\n\"HideInLegacyMenu\"=dword:00000000\r\n"
				+ "\"RootTitle\"=\"Synology Photos permissions\"\r\n"));
		assertTrue(content.contains("[HKEY_CURRENT_USER\\Software\\synoRight\\Menu\\Entries\\01]\r\n\"Title\"=\"Folder permissions…\"\r\n"
				+ "\"Args\"=\"-c \\\"D:\\\\conf dir\\\" rights \\\"%V\\\"\"\r\n\"SeparatorBefore\"=dword:00000000"));
		assertTrue(content.contains("[HKEY_CURRENT_USER\\Software\\synoRight\\Menu\\Entries\\02]\r\n\"Title\"=\"Synchronization status…\"\r\n"
				+ "\"Args\"=\"-c \\\"D:\\\\conf dir\\\" sync\"\r\n\"SeparatorBefore\"=dword:00000001"));
	}

	@Test
	void registre_menuClassiqueAussiInstalle_masqueDansAfficherPlusDOptions() {
		final String content = ModernMenuInstaller.buildRegFileContent(ITEMS, "C:\\synoRightGui.exe", Paths.get("D:\\conf"), "P:\\", true);

		assertTrue(content.contains("\"HideInLegacyMenu\"=dword:00000001\r\n"));
	}

	@Test
	void psQuote_apostrophesDoublees() {
		assertEquals("'C:\\l''album'", ModernMenuInstaller.psQuote("C:\\l'album"));
	}

	private static Optional<ModernMenuInstaller.InstalledPackage> installed(final String version, final String location) {
		return Optional.of(new ModernMenuInstaller.InstalledPackage(version, location));
	}

	@Test
	void packageAction_absent_ajout() {
		assertEquals(ModernMenuInstaller.PackageAction.ADD,
				ModernMenuInstaller.packageAction(Optional.empty(), Paths.get("C:\\Apps\\synoRight"), "0.4.0.0"));
	}

	@Test
	void packageAction_memeDossierVersionInferieure_miseAJour() {
		assertEquals(ModernMenuInstaller.PackageAction.UPGRADE,
				ModernMenuInstaller.packageAction(installed("0.3.0.0", "C:\\Apps\\synoRight"), Paths.get("c:\\apps\\synoright\\"), "0.4.0.0"));
	}

	@Test
	void packageAction_memeVersion_reinstallation() {
		assertEquals(ModernMenuInstaller.PackageAction.REINSTALL,
				ModernMenuInstaller.packageAction(installed("0.4.0.0", "C:\\Apps\\synoRight"), Paths.get("C:\\Apps\\synoRight"), "0.4.0.0"));
	}

	@Test
	void packageAction_autreDossier_reinstallation() {
		assertEquals(ModernMenuInstaller.PackageAction.REINSTALL,
				ModernMenuInstaller.packageAction(installed("0.3.0.0", "C:\\Old\\synoRight"), Paths.get("C:\\Apps\\synoRight"), "0.4.0.0"));
	}

	@Test
	void packageAction_versionInstalleeSuperieure_reinstallation() {
		assertEquals(ModernMenuInstaller.PackageAction.REINSTALL,
				ModernMenuInstaller.packageAction(installed("0.5.0.0", "C:\\Apps\\synoRight"), Paths.get("C:\\Apps\\synoRight"), "0.4.0.0"));
	}

	@Test
	void packageVersion_conversionMaven() {
		assertEquals("0.4.0.0", ModernMenuInstaller.packageVersion("0.4-SNAPSHOT"));
		assertEquals("1.2.3.0", ModernMenuInstaller.packageVersion("1.2.3"));
	}

	@Test
	void dllName_versionDansLeNom() {
		assertEquals("synoRightMenu-0.4.dll", ModernMenuInstaller.dllName("0.4"));
	}

	@Test
	void staleDlls_toutesSaufLaVersionCourante() {
		assertEquals(List.of("synoRightMenu.dll", "synoRightMenu-0.3.dll", "SYNORIGHTMENU-0.2.DLL"),
				ModernMenuInstaller.staleDlls(List.of("synoRightMenu.dll", "synoRightMenu-0.3.dll", "synoRightMenu-0.4.dll",
						"synoRight.msix", "SYNORIGHTMENU-0.2.DLL"), "0.4"));
	}
}
