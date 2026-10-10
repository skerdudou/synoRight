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

	private static final List<String> JAR_LAUNCHER = List.of("C:\\Java\\bin\\java.exe", "-jar", "D:\\apps\\synoRight.jar");
	private static final Path CONFIG = Paths.get("D:\\apps");

	private static String buildContent() {
		return buildContent(JAR_LAUNCHER);
	}

	private static String buildContent(final List<String> launcher) {
		return ContextMenuInstaller.buildRegFileContent(
				List.of(new Group("3", "famille"), new Group("5", "amis")),
				"\\\\NAS\\photo", launcher, CONFIG);
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
	void consultation_droitsDuDossierEtStatus() {
		final String content = buildContent();

		assertTrue(content.contains("\\shell\\01show]"));
		assertTrue(content.contains("\"MUIVerb\"=\"Voir les droits de ce dossier\""));
		assertTrue(content.contains(" show \\\"%V\\\"\""));
		assertTrue(content.contains("\\shell\\02status]"));
		assertTrue(content.contains(" status\""));
	}

	@Test
	void actionsFull_presentesSansCible() {
		final String content = buildContent();

		assertTrue(content.contains("\\shell\\03dbToFs]"));
		assertTrue(content.contains(" pull\""));
		assertTrue(content.contains("\\shell\\04fsToDb]"));
		assertTrue(content.contains(" push\""));
	}

	@Test
	void actionsParGroupe_avecGroupeEtRepertoireCible() {
		final String content = buildContent();

		assertTrue(content.contains("\\shell\\05addfamille]"));
		assertTrue(content.contains("\"MUIVerb\"=\"Ajouter famille\""));
		assertTrue(content.contains(" grant \\\"famille\\\" \\\"%V\\\"\""));
		assertTrue(content.contains("\\shell\\06delfamille]"));
		assertTrue(content.contains("\\shell\\07addamis]"));
		assertTrue(content.contains("\\shell\\08delamis]"));
		assertTrue(content.contains(" revoke \\\"amis\\\" \\\"%V\\\"\""));
	}

	@Test
	void commandes_pointentVersJavaJarEtConfig() {
		final String content = buildContent();

		assertTrue(content.contains("@=\"\\\"C:\\\\Java\\\\bin\\\\java.exe\\\" -jar \\\"D:\\\\apps\\\\synoRight.jar\\\" -c \\\"D:\\\\apps\\\""));
	}

	@Test
	void commandes_lanceurExe() {
		final String content = buildContent(List.of("C:\\Program Files\\synoRight\\synoRight.exe"));

		assertTrue(content.contains("@=\"\\\"C:\\\\Program Files\\\\synoRight\\\\synoRight.exe\\\" -c \\\"D:\\\\apps\\\" pull\""));
	}

	@Test
	void separateurs_apresStatusFsToDbEtEntreesDeSuppression() {
		final String content = buildContent();

		final int status = content.indexOf("02status]");
		assertTrue(content.indexOf("\"CommandFlags\"=dword:00004140", status) > status);
		final int fsToDb = content.indexOf("04fsToDb]");
		assertTrue(content.indexOf("\"CommandFlags\"=dword:00004140", fsToDb) > fsToDb);

		// pas de flags sur les entrées d'ajout
		final int addFamille = content.indexOf("05addfamille]");
		final int delFamille = content.indexOf("06delfamille]");
		assertFalse(content.substring(addFamille, delFamille).contains("CommandFlags"));
	}

	@Test
	void desinstallation_supprimeLesTroisCles() {
		final List<List<String>> commands = ContextMenuInstaller.buildUninstallCommands();

		assertEquals(3, commands.size());
		assertTrue(commands.stream().allMatch(c -> c.get(0).equals("reg") && c.get(1).equals("delete") && c.contains("/f")));
	}
}
