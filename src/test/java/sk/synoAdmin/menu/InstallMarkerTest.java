package sk.synoAdmin.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InstallMarkerTest {

	private static final String OUTPUT = "\r\nHKEY_CURRENT_USER\\Software\\synoRight\\Install\r\n"
			+ "    Version    REG_SZ    0.4\r\n"
			+ "    Launcher    REG_SZ    C:\\Users\\a b\\AppData\\Local\\Programs\\synoRight\\synoRightGui.exe\r\n"
			+ "    ConfigDir    REG_SZ    C:\\Users\\a b\\.synoRight\r\n\r\n";

	@Test
	void parseSortieDeRegQuery() {
		final InstallMarker marker = InstallMarker.parse(OUTPUT).orElseThrow();
		assertEquals("0.4", marker.version());
		assertEquals("C:\\Users\\a b\\AppData\\Local\\Programs\\synoRight\\synoRightGui.exe", marker.launcher());
		assertEquals("C:\\Users\\a b\\.synoRight", marker.configDir());
		assertNull(marker.certThumbprint());
		assertNull(marker.attemptedVersion());
		assertNull(marker.attemptedLauncher());
	}

	@Test
	void parseTentative() {
		final InstallMarker marker = InstallMarker.parse("    AttemptedVersion    REG_SZ    0.5\r\n"
				+ "    AttemptedLauncher    REG_SZ    C:\\a b\\synoRightGui.exe\r\n").orElseThrow();
		assertEquals("0.5", marker.attemptedVersion());
		assertEquals("C:\\a b\\synoRightGui.exe", marker.attemptedLauncher());
	}

	@Test
	void parseVideOuSansValeur() {
		assertTrue(InstallMarker.parse("").isEmpty());
		assertTrue(InstallMarker.parse(null).isEmpty());
	}

	@Test
	void regFileComplet() {
		final String reg = new InstallMarker("0.4", "C:\\Users\\a b\\x.exe", "C:\\cfg", "ABCD", "0.5", "C:\\y.exe").toRegFile();
		assertTrue(reg.startsWith("Windows Registry Editor Version 5.00\r\n"));
		assertTrue(reg.contains("[-" + InstallMarker.KEY + "]\r\n"));
		assertTrue(reg.contains("[" + InstallMarker.KEY + "]\r\n"));
		assertTrue(reg.contains("\"Launcher\"=\"C:\\\\Users\\\\a b\\\\x.exe\"\r\n"));
		assertTrue(reg.contains("\"CertThumbprint\"=\"ABCD\"\r\n"));
		assertTrue(reg.contains("\"AttemptedVersion\"=\"0.5\"\r\n"));
		assertTrue(reg.contains("\"AttemptedLauncher\"=\"C:\\\\y.exe\"\r\n"));
	}

	@Test
	void regFileOmetLesValeursNulles() {
		final String reg = new InstallMarker("0.4", null, null, null, null, null).toRegFile();
		assertTrue(reg.contains("\"Version\"=\"0.4\""));
		assertFalse(reg.contains("Launcher"));
		assertFalse(reg.contains("ConfigDir"));
		assertFalse(reg.contains("CertThumbprint"));
		assertFalse(reg.contains("AttemptedVersion"));
	}

	@Test
	void withAttempt() {
		final InstallMarker marker = new InstallMarker("0.4", "l", "c", "t", null, null).withAttempt("0.5", "m");
		assertEquals("0.5", marker.attemptedVersion());
		assertEquals("m", marker.attemptedLauncher());
		assertEquals("0.4", marker.version());
		assertEquals("l", marker.launcher());
	}
}
