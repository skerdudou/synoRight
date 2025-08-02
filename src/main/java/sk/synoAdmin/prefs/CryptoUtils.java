package sk.synoAdmin.prefs;

import java.security.Key;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class CryptoUtils {

	private static final String SECRET_KEY = "uc97SeC3eR5G159F";

	/**
	 * Chiffre en blowfish.
	 *
	 * @param password le mdp a chiffrer
	 * @return le mdp chiffré et en base 64.
	 */
	public static String encrypt(final String password) {
		try {
			final Key clef = new SecretKeySpec(SECRET_KEY.getBytes("ISO-8859-2"), "Blowfish");
			final Cipher cipher = Cipher.getInstance("Blowfish");
			cipher.init(Cipher.ENCRYPT_MODE, clef);
			return Base64.getEncoder().encodeToString(cipher.doFinal(password.getBytes()));
		} catch (final Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Déchiffre.
	 *
	 * @param password le mdp chiffré
	 * @return le mdp en clair
	 */
	public static String decrypt(final String password) {
		if (password == null) return null;
		try {
			final Key clef = new SecretKeySpec(SECRET_KEY.getBytes("ISO-8859-2"), "Blowfish");
			final Cipher cipher = Cipher.getInstance("Blowfish");
			cipher.init(Cipher.DECRYPT_MODE, clef);
			return new String(cipher.doFinal(Base64.getDecoder().decode(password)));
		} catch (final Exception e) {
			throw new RuntimeException(e);
		}
	}
}
