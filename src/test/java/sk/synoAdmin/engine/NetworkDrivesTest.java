package sk.synoAdmin.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.WINDOWS)
class NetworkDrivesTest {

	private static final Function<Character, Optional<String>> Z_IS_NAS = letter -> Optional
			.ofNullable(Map.of('Z', "\\\\NAS\\photo").get(Character.toUpperCase(letter)));

	@Test
	void lecteurMappe_convertiEnUnc() {
		assertEquals(Paths.get("\\\\NAS\\photo\\2026\\Vacances"), NetworkDrives.toUnc(Paths.get("Z:\\2026\\Vacances"), Z_IS_NAS));
		assertEquals(Paths.get("\\\\NAS\\photo\\"), NetworkDrives.toUnc(Paths.get("z:\\"), Z_IS_NAS));
	}

	@Test
	void lecteurLocalOuUnc_inchanges() {
		final Path local = Paths.get("C:\\photos\\2026");
		final Path unc = Paths.get("\\\\NAS\\photo\\2026");

		assertEquals(local, NetworkDrives.toUnc(local, Z_IS_NAS));
		assertEquals(unc, NetworkDrives.toUnc(unc, Z_IS_NAS));
	}
}
