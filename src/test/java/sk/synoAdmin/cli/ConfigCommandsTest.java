package sk.synoAdmin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sk.synoAdmin.ConsoleUtil;
import sk.synoAdmin.engine.Group;
import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.synology.SynoGroup;
import sk.synoAdmin.ui.ConsoleInteraction;

class ConfigCommandsTest {

	static {
		if (System.getProperty("synoRight.keyFile") == null) {
			System.setProperty("synoRight.keyFile", System.getProperty("java.io.tmpdir") + "/synoRight-test.key");
		}
	}

	@AfterEach
	void restore() {
		ConsoleUtil.setInteraction(new ConsoleInteraction());
	}

	@Test
	void toGroup_nomCourtExistantConserve(@TempDir final Path dir) {
		final Preferences existing = new Preferences(dir, dir.toString(), "https://nas", "admin", null,
				List.of(new Group("65536", "Smith")), false);

		final Group group = ConfigCommands.toGroup(new SynoGroup(65536, "Smith family"), existing, List.of());

		assertEquals("65536", group.getId());
		assertEquals("Smith", group.getName());
	}

	@Test
	void toGroup_nomDuNasParDefaut() {
		assertEquals("Maison", ConfigCommands.toGroup(new SynoGroup(65538, "Maison"), null, List.of()).getName());
	}

	@Test
	void toGroup_nomInutilisable_redemande() {
		ConsoleUtil.setInput(new ByteArrayInputStream("smith\nSmith2\n".getBytes(StandardCharsets.UTF_8)));

		// « a:b » interdit, puis « smith » déjà pris (sans tenir compte de la casse), puis « Smith2 » accepté
		final Group group = ConfigCommands.toGroup(new SynoGroup(7, "a:b"), null, List.of(new Group("65536", "Smith")));

		assertEquals("Smith2", group.getName());
	}
}
