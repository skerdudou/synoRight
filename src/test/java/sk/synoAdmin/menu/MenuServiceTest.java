package sk.synoAdmin.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import sk.synoAdmin.UserException;

class MenuServiceTest {

	@Test
	void installBoth_echecWindows11_menuClassiqueDejaInstalle() {
		final List<String> calls = new ArrayList<>();

		final UserException e = assertThrows(UserException.class, () -> MenuService.installBoth(
				() -> calls.add("classique"),
				() -> {
					calls.add("modern");
					throw new UserException("autorisation refusée");
				}));

		assertEquals(List.of("classique", "modern"), calls);
		assertTrue(e.getMessage().contains("Classic menu installed"));
		assertTrue(e.getMessage().contains("autorisation refusée"));
	}

	@Test
	void installBoth_renvoieEmpreinteDuMenuWindows11() {
		assertEquals("ABCD", MenuService.installBoth(() -> {
		}, () -> "ABCD"));
	}

	private static final InstallMarker PREVIOUS = new InstallMarker("0.3", "D:\\ancien\\synoRightGui.exe", "C:\\ancienne conf", "ABCD",
			"0.4", "C:\\Programs\\synoRight\\synoRightGui.exe");

	@Test
	void markerToWrite_installation_repereNeuf() {
		assertEquals(Optional.of(new InstallMarker("0.4", "C:\\app\\synoRightGui.exe", "C:\\conf", "EF01", null, null)),
				MenuService.markerToWrite(Optional.of(PREVIOUS), "0.4", "C:\\app\\synoRightGui.exe", "C:\\conf", "EF01", true));
		assertEquals(Optional.of(new InstallMarker("0.4", "C:\\app\\synoRightGui.exe", "C:\\conf", null, null, null)),
				MenuService.markerToWrite(Optional.empty(), "0.4", "C:\\app\\synoRightGui.exe", "C:\\conf", null, true));
	}

	@Test
	void markerToWrite_rafraichissement_garderVersionLanceurEtTentative() {
		// le package Windows 11 n'est pas réinstallé : la réparation doit rester nécessaire après un déplacement
		assertEquals(Optional.of(new InstallMarker("0.3", "D:\\ancien\\synoRightGui.exe", "C:\\conf", "ABCD", "0.4",
				"C:\\Programs\\synoRight\\synoRightGui.exe")),
				MenuService.markerToWrite(Optional.of(PREVIOUS), "0.4", "C:\\app\\synoRightGui.exe", "C:\\conf", "ABCD", false));
	}

	@Test
	void markerToWrite_rafraichissementSansRepere_rienEcrit() {
		assertEquals(Optional.empty(),
				MenuService.markerToWrite(Optional.empty(), "0.4", "C:\\app\\synoRightGui.exe", "C:\\conf", null, false));
	}
}
