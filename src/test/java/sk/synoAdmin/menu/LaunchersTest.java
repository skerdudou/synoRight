package sk.synoAdmin.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;

import sk.synoAdmin.UserException;

class LaunchersTest {
	@Test
	void exe_lanceurGuiAcoteDuLanceurConsole() {
		assertEquals(List.of("C:\\Apps\\synoRight\\synoRightGui.exe"),
				Launchers.gui("C:\\Apps\\synoRight\\synoRight.exe", Paths.get("C:\\Apps\\synoRight\\runtime"), null));
	}

	@Test
	void jar_javawAvecSousCommandeGui() {
		assertEquals(List.of("C:\\Java\\bin\\javaw.exe", "-jar", "D:\\apps\\synoRight.jar", "gui"),
				Launchers.gui(null, Paths.get("C:\\Java"), Paths.get("D:\\apps\\synoRight.jar")));
	}

	@Test
	void ni_exe_ni_jar_refuse() {
		assertThrows(UserException.class, () -> Launchers.gui(null, Paths.get("C:\\Java"), Paths.get("D:\\classes")));
	}
}
