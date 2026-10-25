package sk.synoAdmin.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sk.synoAdmin.menu.MenuRepair.InstalledMenus;
import sk.synoAdmin.prefs.Preferences;

class MenuRepairTest {

	private static final String LAUNCHER = "C:\\Programs\\synoRight\\synoRightGui.exe";
	private static final String OLD_LAUNCHER = "D:\\ancien\\synoRightGui.exe";
	private static final InstalledMenus NONE = new InstalledMenus(false, false);
	private static final InstalledMenus CLASSIC = new InstalledMenus(true, false);

	private static Optional<InstallMarker> marker(final String version, final String launcher) {
		return Optional.of(new InstallMarker(version, launcher, "C:\\cfg", null, null, null));
	}

	private static Optional<InstallMarker> attempted(final String version, final String launcher, final String attemptedVersion,
			final String attemptedLauncher) {
		return Optional.of(new InstallMarker(version, launcher, "C:\\cfg", null, attemptedVersion, attemptedLauncher));
	}

	@Test
	void needsRepair_aucunMenu_fauxMemeForce() {
		assertFalse(MenuRepair.needsRepair(Optional.empty(), NONE, "0.4", LAUNCHER, false, true));
		assertFalse(MenuRepair.needsRepair(Optional.empty(), NONE, "0.4", LAUNCHER, true, true));
	}

	@Test
	void needsRepair_repereAbsent() {
		assertTrue(MenuRepair.needsRepair(Optional.empty(), CLASSIC, "0.4", LAUNCHER, false, true));
	}

	@Test
	void needsRepair_versionDifferente() {
		assertTrue(MenuRepair.needsRepair(marker("0.3", LAUNCHER), CLASSIC, "0.4", LAUNCHER, false, true));
	}

	@Test
	void needsRepair_aJour() {
		assertFalse(MenuRepair.needsRepair(marker("0.4", LAUNCHER), CLASSIC, "0.4", LAUNCHER, false, true));
	}

	@Test
	void needsRepair_memeVersionApplicationDeplacee() {
		assertTrue(MenuRepair.needsRepair(marker("0.4", OLD_LAUNCHER), CLASSIC, "0.4", LAUNCHER, false, true));
	}

	@Test
	void needsRepair_dejaTentee_versionEtLanceur() {
		final Optional<InstallMarker> marker = attempted("0.3", OLD_LAUNCHER, "0.4", LAUNCHER);
		assertFalse(MenuRepair.needsRepair(marker, CLASSIC, "0.4", LAUNCHER, false, true));
		assertTrue(MenuRepair.needsRepair(marker, CLASSIC, "0.4", "E:\\autre\\synoRightGui.exe", false, true));
		assertTrue(MenuRepair.needsRepair(marker, CLASSIC, "0.4", LAUNCHER, true, true));
	}

	@Test
	void needsRepair_menuWindows11_sansExe_reparationAutoIgnoree() {
		final InstalledMenus modern = new InstalledMenus(false, true);
		final InstalledMenus both = new InstalledMenus(true, true);
		// jar : le package ne peut pas être installé, les menus ne sont pas repointés automatiquement
		assertFalse(MenuRepair.needsRepair(Optional.empty(), modern, "0.4", LAUNCHER, false, false));
		assertFalse(MenuRepair.needsRepair(marker("0.3", LAUNCHER), both, "0.4", LAUNCHER, false, false));
		// réparation forcée : tentée (échoue avec le message dédié)
		assertTrue(MenuRepair.needsRepair(Optional.empty(), modern, "0.4", LAUNCHER, true, false));
		// menu classique seul : réparable depuis le jar
		assertTrue(MenuRepair.needsRepair(Optional.empty(), CLASSIC, "0.4", LAUNCHER, false, false));
		assertTrue(MenuRepair.needsRepair(Optional.empty(), modern, "0.4", LAUNCHER, false, true));
	}

	@Test
	void mode_forceEtTentativeNotee() {
		assertTrue(MenuRepair.Mode.FORCED.force());
		assertTrue(MenuRepair.Mode.FORCED.recordAttempt());
		assertFalse(MenuRepair.Mode.STARTUP.force());
		assertTrue(MenuRepair.Mode.STARTUP.recordAttempt());
		// installateur (--if-needed) : un échec n'est pas noté, l'IHM relancée retente et l'affiche
		assertFalse(MenuRepair.Mode.IF_NEEDED.force());
		assertFalse(MenuRepair.Mode.IF_NEEDED.recordAttempt());
	}

	@Test
	void configDir_ordreDePriorite() {
		final Optional<String> modern = Optional.of("D:\\modern");
		final Optional<String> classic = Optional.of("\"C:\\a b\\synoRightGui.exe\" -c \"D:\\conf dir\" rights --collect \"%V\"");

		assertEquals(Path.of("C:\\cfg"), MenuRepair.configDir(marker("0.3", LAUNCHER), modern, classic));
		assertEquals(Path.of("D:\\modern"), MenuRepair.configDir(Optional.empty(), modern, classic));
		assertEquals(Path.of("D:\\conf dir"), MenuRepair.configDir(Optional.empty(), Optional.empty(), classic));
		assertEquals(Preferences.resolveConfigDir(null), MenuRepair.configDir(Optional.empty(), Optional.empty(), Optional.empty()));
	}

	@Test
	void configDir_repereSansDossier_sourceSuivante() {
		final Optional<InstallMarker> attemptOnly = Optional.of(new InstallMarker(null, null, null, null, "0.4", LAUNCHER));
		assertEquals(Path.of("D:\\modern"), MenuRepair.configDir(attemptOnly, Optional.of("D:\\modern"), Optional.empty()));
	}

	@Test
	void classicCommand_premiereCommandeDeRegQuery() {
		final String output = "\r\nHKEY_CURRENT_USER\\Software\\Classes\\Directory\\ContextMenus\\SynoRight\\shell\\01rights\r\n"
				+ "    MUIVerb    REG_SZ    Droits\r\n\r\n"
				+ "HKEY_CURRENT_USER\\Software\\Classes\\Directory\\ContextMenus\\SynoRight\\shell\\01rights\\command\r\n"
				+ "    (Par défaut)    REG_SZ    \"C:\\a b\\synoRightGui.exe\" -c \"D:\\conf dir\" rights \"%V\"\r\n\r\n"
				+ "HKEY_CURRENT_USER\\Software\\Classes\\Directory\\ContextMenus\\SynoRight\\shell\\02sync\\command\r\n"
				+ "    (Default)    REG_SZ    autre\r\n";

		assertEquals(Optional.of("\"C:\\a b\\synoRightGui.exe\" -c \"D:\\conf dir\" rights \"%V\""), MenuRepair.parseClassicCommand(output));
		assertEquals(Optional.empty(), MenuRepair.parseClassicCommand(""));
	}

	@Test
	void target_selonMenusInstalles() {
		assertEquals(MenuTarget.ALL, MenuRepair.target(new InstalledMenus(true, true)));
		assertEquals(MenuTarget.MODERN, MenuRepair.target(new InstalledMenus(false, true)));
		assertEquals(MenuTarget.CLASSIC, MenuRepair.target(CLASSIC));
	}

	@Test
	void tryLock_secondRefuseTantQueLePremierEstTenu(@TempDir final Path dir) throws IOException {
		final Path file = dir.resolve("repair.lock");
		final Optional<FileLock> first = MenuRepair.tryLock(file);
		assertTrue(first.isPresent());
		assertTrue(MenuRepair.tryLock(file).isEmpty());

		first.get().channel().close();
		final Optional<FileLock> again = MenuRepair.tryLock(file);
		assertTrue(again.isPresent());
		again.get().channel().close();
	}
}
