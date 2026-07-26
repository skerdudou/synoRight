package sk.synoAdmin.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class CryptoUtilsTest {

	@Test
	void encryptPuisDecrypt_retrouveLaValeur() {
		final String password = "unM0tDePasse!é&à";

		assertEquals(password, CryptoUtils.decrypt(CryptoUtils.encrypt(password)));
	}

	@Test
	void encrypt_neRetournePasLaValeurEnClair() {
		assertNotEquals("secret", CryptoUtils.encrypt("secret"));
	}

	@Test
	void decrypt_null_retourneNull() {
		assertNull(CryptoUtils.decrypt(null));
	}
}
