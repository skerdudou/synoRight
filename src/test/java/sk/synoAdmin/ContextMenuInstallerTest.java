package sk.synoAdmin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;

import sk.synoAdmin.engine.Group;

class ContextMenuInstallerTest {

	private static final Path JAVA_EXE = Paths.get("C:\\Java\\bin\\java.exe");
	private static final Path JAR = Paths.get("D:\\apps\\synoRight.jar");
	private static final Path CONFIG = Paths.get("D:\\apps");

	private static String buildContent() {
		return ContextMenuInstaller.buildRegFileContent(
				List.of(new Group("3", "famille"), new Group("5", "amis")),
				"\\\\NAS\\photo", JAVA_EXE, JAR, CONFIG);
	}

	@Test
	void entete_formatRegVersion5() {
		assertTrue(buildContent().startsWith("Windows Registry Editor Version 5.00\r\n"));
	}

	@Test
	void clesRacines_sousHkcuAvecSousMenu() {
		final String content = buildContent();

		assertTrue(content.contains("[HKEY_CURRENT_USER\\Software\\Classes\\Directory\\shell\\01SynoRight]"));
		assertTrue(content.contains("[HKEY_CURRENT_USER\\Software\\Classes\\Directory\\Background\\shell\\01SynoRight]"));
		assertTrue(content.contains("\"ExtendedSubCommandsKey\"=\"Directory\\\\ContextMenus\\\\SynoRight\""));
	}

	@Test
	void appliesTo_restreintAuDossierPhotos() {
		// \\NAS\photo\* avec les backslashs doublés au format .reg
		assertTrue(buildContent().contains("\"AppliesTo\"=\"\\\\\\\\NAS\\\\photo\\\\*\""));
	}

	@Test
	void actionsFull_presentesSansCible() {
		final String content = buildContent();

		assertTrue(content.contains("\\shell\\01dbToFs]"));
		assertTrue(content.contains(" -a FULL_DB_TO_FS\""));
		assertTrue(content.contains("\\shell\\02fsToDb]"));
		assertTrue(content.contains(" -a FULL_FS_TO_DB\""));
	}

	@Test
	void actionsParGroupe_avecGroupeEtRepertoireCible() {
		final String content = buildContent();

		assertTrue(content.contains("\\shell\\03addfamille]"));
		assertTrue(content.contains("\"MUIVerb\"=\"Ajouter famille\""));
		assertTrue(content.contains(" -a ADD_GRP -g \\\"famille\\\" -d \\\"%V\\\"\""));
		assertTrue(content.contains("\\shell\\04delfamille]"));
		assertTrue(content.contains("\\shell\\05addamis]"));
		assertTrue(content.contains("\\shell\\06delamis]"));
		assertTrue(content.contains(" -a DEL_GRP -g \\\"amis\\\" -d \\\"%V\\\"\""));
	}

	@Test
	void commandes_pointentVersJavaJarEtConfig() {
		final String content = buildContent();

		assertTrue(content.contains("@=\"\\\"C:\\\\Java\\\\bin\\\\java.exe\\\" -jar \\\"D:\\\\apps\\\\synoRight.jar\\\" -c \\\"D:\\\\apps\\\""));
	}

	@Test
	void separateurs_surFsToDbEtEntreesDeSuppression() {
		final String content = buildContent();

		final int fsToDb = content.indexOf("02fsToDb]");
		final int flagsAfterFsToDb = content.indexOf("\"CommandFlags\"=dword:00004140", fsToDb);
		assertTrue(flagsAfterFsToDb > fsToDb);

		// pas de flags sur les entrées d'ajout
		final int addFamille = content.indexOf("03addfamille]");
		final int delFamille = content.indexOf("04delfamille]");
		assertFalse(content.substring(addFamille, delFamille).contains("CommandFlags"));
	}

	@Test
	void desinstallation_supprimeLesTroisCles() {
		final List<List<String>> commands = ContextMenuInstaller.buildUninstallCommands();

		assertEquals(3, commands.size());
		assertTrue(commands.stream().allMatch(c -> c.get(0).equals("reg") && c.get(1).equals("delete") && c.contains("/f")));
	}
}
