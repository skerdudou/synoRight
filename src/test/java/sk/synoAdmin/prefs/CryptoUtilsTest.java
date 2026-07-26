package sk.synoAdmin.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

class CryptoUtilsTest {

	static {
		// ne pas toucher la clé réelle de l'utilisateur quand les tests tournent hors de maven
		if (System.getProperty("synoRight.keyFile") == null) {
			System.setProperty("synoRight.keyFile", System.getProperty("java.io.tmpdir") + "/synoRight-test.key");
		}
	}

	// réplique de l'ancien chiffrement Blowfish pour tester la migration
	static String legacyEncrypt(final String password) throws Exception {
		final var clef = new SecretKeySpec("uc97SeC3eR5G159F".getBytes("ISO-8859-2"), "Blowfish");
		final Cipher cipher = Cipher.getInstance("Blowfish");
		cipher.init(Cipher.ENCRYPT_MODE, clef);
		return Base64.getEncoder().encodeToString(cipher.doFinal(password.getBytes()));
	}

	@Test
	void encryptPuisDecrypt_retrouveLaValeur() {
		final String password = "unM0tDePasse!é&à";

		assertEquals(password, CryptoUtils.decrypt(CryptoUtils.encrypt(password)));
	}

	@Test
	void encrypt_formatCourantEtIvAleatoire() {
		final String first = CryptoUtils.encrypt("secret");
		final String second = CryptoUtils.encrypt("secret");

		assertTrue(CryptoUtils.isCurrentFormat(first));
		// IV aléatoire : deux chiffrements du même mot de passe diffèrent
		assertNotEquals(first, second);
	}

	@Test
	void encrypt_neRetournePasLaValeurEnClair() {
		assertFalse(CryptoUtils.encrypt("secret").contains("secret"));
	}

	@Test
	void decrypt_null_retourneNull() {
		assertNull(CryptoUtils.decrypt(null));
	}

	@Test
	void decrypt_ancienFormatBlowfish_migrable() throws Exception {
		final String legacy = legacyEncrypt("ancienSecret");

		assertFalse(CryptoUtils.isCurrentFormat(legacy));
		assertEquals("ancienSecret", CryptoUtils.decrypt(legacy));
	}
}
