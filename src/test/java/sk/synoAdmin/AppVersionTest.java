package sk.synoAdmin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AppVersionTest {

	@Test
	void compareNumeriquementParSegment() {
		assertTrue(AppVersion.compare("0.10", "0.9") > 0);
		assertTrue(AppVersion.compare("1.0", "0.99") > 0);
		assertTrue(AppVersion.compare("0.9", "0.10") < 0);
	}

	@Test
	void segmentAbsentVautZero() {
		assertEquals(0, AppVersion.compare("0.4", "0.4.0"));
	}

	@Test
	void suffixeInferieurALaVersionSansSuffixe() {
		assertTrue(AppVersion.compare("0.4-beta", "0.4") < 0);
		assertTrue(AppVersion.compare("0.4", "0.4-beta") > 0);
		assertTrue(AppVersion.compare("0.4-beta", "0.4-rc") < 0);
	}

	@Test
	void segmentNonNumeriqueApresLesNumeriques() {
		assertTrue(AppVersion.compare("1.a", "1.5") > 0);
	}
}
