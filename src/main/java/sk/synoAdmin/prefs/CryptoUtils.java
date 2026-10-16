package sk.synoAdmin.prefs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.Key;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import sk.synoAdmin.i18n.Messages;

/**
 * Chiffrement du mot de passe en AES-256-GCM avec une clé aléatoire propre au poste,
 * stockée dans ~/.synoRight/key avec des permissions restreintes au propriétaire
 * (POSIX sous Linux/macOS, ACL NTFS sous Windows).
 *
 * L'ancien format (Blowfish avec clé en dur dans le code) reste déchiffrable pour
 * permettre la migration automatique des configurations existantes.
 */
public final class CryptoUtils {

	private static final String CURRENT_PREFIX = "v2:";
	private static final int KEY_LENGTH_BYTES = 32;
	private static final int GCM_IV_LENGTH = 12;
	private static final int GCM_TAG_BITS = 128;

	private static final String LEGACY_SECRET_KEY = "uc97SeC3eR5G159F";

	private static SecretKeySpec key;

	private CryptoUtils() {
		// utility
	}

	/**
	 * Indique si la valeur est déjà chiffrée au format courant (AES-GCM).
	 */
	static boolean isCurrentFormat(final String value) {
		return value != null && value.startsWith(CURRENT_PREFIX);
	}

	/**
	 * Chiffre en AES-GCM avec la clé du poste.
	 *
	 * @param password le mdp a chiffrer
	 * @return le mdp chiffré : préfixe de version + base64(iv + données)
	 */
	public static String encrypt(final String password) {
		try {
			final byte[] iv = new byte[GCM_IV_LENGTH];
			new SecureRandom().nextBytes(iv);

			final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, getKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
			final byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));

			final byte[] payload = new byte[iv.length + encrypted.length];
			System.arraycopy(iv, 0, payload, 0, iv.length);
			System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);

			return CURRENT_PREFIX + Base64.getEncoder().encodeToString(payload);
		} catch (final Exception e) {
			throw new RuntimeException(Messages.get("prefs.crypto.encryptError"), e);
		}
	}

	/**
	 * Déchiffre une valeur au format courant ou à l'ancien format Blowfish.
	 *
	 * @param value le mdp chiffré
	 * @return le mdp en clair
	 */
	public static String decrypt(final String value) {
		if (value == null) {
			return null;
		}
		if (!isCurrentFormat(value)) {
			return legacyDecrypt(value);
		}

		try {
			final byte[] payload = Base64.getDecoder().decode(value.substring(CURRENT_PREFIX.length()));

			final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(GCM_TAG_BITS, payload, 0, GCM_IV_LENGTH));

			return new String(cipher.doFinal(payload, GCM_IV_LENGTH, payload.length - GCM_IV_LENGTH), StandardCharsets.UTF_8);
		} catch (final Exception e) {
			throw new RuntimeException(Messages.get("prefs.crypto.decryptError", keyFile()), e);
		}
	}

	private static String legacyDecrypt(final String password) {
		try {
			final Key clef = new SecretKeySpec(LEGACY_SECRET_KEY.getBytes("ISO-8859-2"), "Blowfish");
			final Cipher cipher = Cipher.getInstance("Blowfish");
			cipher.init(Cipher.DECRYPT_MODE, clef);
			return new String(cipher.doFinal(Base64.getDecoder().decode(password)));
		} catch (final Exception e) {
			throw new RuntimeException(Messages.get("prefs.crypto.legacyDecryptError"), e);
		}
	}

	/**
	 * Fichier contenant la clé de chiffrement du poste
	 * (surchargeable via -DsynoRight.keyFile pour les tests).
	 */
	static Path keyFile() {
		final String override = System.getProperty("synoRight.keyFile");
		if (override != null) {
			return Paths.get(override);
		}
		return Paths.get(System.getProperty("user.home"), ".synoRight", "key");
	}

	private static synchronized Key getKey() {
		if (key == null) {
			key = loadOrCreateKey();
		}
		return key;
	}

	private static SecretKeySpec loadOrCreateKey() {
		final Path file = keyFile();
		try {
			if (Files.isRegularFile(file)) {
				final byte[] bytes = Files.readAllBytes(file);
				if (bytes.length != KEY_LENGTH_BYTES) {
					throw new IOException(Messages.get("prefs.crypto.keySize", bytes.length));
				}
				return new SecretKeySpec(bytes, "AES");
			}

			final byte[] bytes = new byte[KEY_LENGTH_BYTES];
			new SecureRandom().nextBytes(bytes);
			Files.createDirectories(file.getParent());
			restrictToOwner(file.getParent());
			Files.write(file, bytes);
			restrictToOwner(file);
			System.out.println(Messages.get("prefs.crypto.keyGenerated", file));
			return new SecretKeySpec(bytes, "AES");
		} catch (final IOException e) {
			throw new RuntimeException(Messages.get("prefs.crypto.keyError", file), e);
		}
	}

	// restreint l'accès au propriétaire : permissions POSIX quand supportées, ACL NTFS sinon
	private static void restrictToOwner(final Path path) throws IOException {
		if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
			Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
					Files.isDirectory(path) ? "rwx------" : "rw-------"));
			return;
		}

		final AclFileAttributeView aclView = Files.getFileAttributeView(path, AclFileAttributeView.class);
		if (aclView != null) {
			final UserPrincipal owner = Files.getOwner(path);
			aclView.setAcl(List.of(AclEntry.newBuilder()
					.setType(AclEntryType.ALLOW)
					.setPrincipal(owner)
					.setPermissions(EnumSet.allOf(AclEntryPermission.class))
					.build()));
		}
	}
}
