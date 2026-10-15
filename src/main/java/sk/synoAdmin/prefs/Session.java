package sk.synoAdmin.prefs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Session Synology Photos (cookie de session, token, device id OTP), mémorisée chiffrée
 * dans un fichier à part de la configuration : la configuration reste éditable à la
 * main et copiable sur un autre poste sans y embarquer de session.
 */
public final class Session {

	public static final String FILENAME = "session" + Preferences.PREF_FILE_NAME + ".properties";

	private static final String KEY_SESSION = "cookieSession";
	private static final String KEY_TOKEN = "synoToken";
	private static final String KEY_DID = "cookieDid";

	private final Path file;
	private String cookieSession;
	private String synoToken;
	private String cookieDid; // device id pour l'OTP

	private Session(final Path file) {
		this.file = file;
	}

	/**
	 * Lit la session du répertoire de configuration (vide si absente ou indéchiffrable).
	 */
	public static Session load(final Path configDir) {
		final Session session = new Session(configDir.resolve(FILENAME));
		if (!Files.isRegularFile(session.file)) {
			return session;
		}

		final Properties prop;
		try {
			prop = PropertiesFiles.load(session.file);
		} catch (final IOException e) {
			throw new RuntimeException("Impossible de lire la session " + session.file, e);
		}
		session.cookieSession = readProtected(prop, KEY_SESSION);
		session.synoToken = readProtected(prop, KEY_TOKEN);
		session.cookieDid = readProtected(prop, KEY_DID);
		return session;
	}

	/**
	 * Déchiffre une valeur ; en clair (ancienne config) telle quelle ; indéchiffrable
	 * (clé changée ?) : absente, un nouveau login la re-créera.
	 */
	static String readProtected(final Properties prop, final String key) {
		final String raw = prop.getProperty(key);
		if (raw == null || !CryptoUtils.isCurrentFormat(raw)) {
			return raw;
		}
		try {
			return CryptoUtils.decrypt(raw);
		} catch (final RuntimeException e) {
			return null;
		}
	}

	/**
	 * Reprend une session historiquement stockée dans le fichier de configuration.
	 *
	 * @return true si la configuration contenait une session
	 */
	boolean importFromConfig(final Properties configProp) {
		boolean found = false;
		for (final String key : new String[] { KEY_SESSION, KEY_TOKEN, KEY_DID }) {
			if (configProp.containsKey(key)) {
				found = true;
			}
		}
		if (found) {
			cookieSession = readProtected(configProp, KEY_SESSION);
			synoToken = readProtected(configProp, KEY_TOKEN);
			cookieDid = readProtected(configProp, KEY_DID);
			save();
		}
		return found;
	}

	public String getCookieSession() {
		return cookieSession;
	}

	public String getSynoToken() {
		return synoToken;
	}

	public String getCookieDid() {
		return cookieDid;
	}

	/**
	 * Mémorise une nouvelle session ; un device id absent conserve le précédent
	 * (il n'est renvoyé par le NAS que lors d'un login avec OTP).
	 */
	public void update(final String cookieSession, final String synoToken, final String cookieDid) {
		this.cookieSession = cookieSession;
		this.synoToken = synoToken;
		if (cookieDid != null) {
			this.cookieDid = cookieDid;
		}
		save();
	}

	/**
	 * Session de travail en mémoire, sans session ouverte (force un vrai login), pour
	 * tester des identifiants sans toucher à la session mémorisée tant que le login
	 * n'a pas réussi (voir {@link #replaceWith(Session)}).
	 *
	 * @param keepDeviceId conserve le device id (même compte : évite de redemander l'OTP)
	 */
	public Session transientCopy(final boolean keepDeviceId) {
		final Session copy = new Session(null);
		if (keepDeviceId) {
			copy.cookieDid = cookieDid;
		}
		return copy;
	}

	/**
	 * Remplace la session mémorisée par une autre (typiquement une copie de travail après login).
	 */
	public void replaceWith(final Session other) {
		cookieSession = other.cookieSession;
		synoToken = other.synoToken;
		cookieDid = other.cookieDid;
		save();
	}

	/**
	 * Oublie la session (y compris le device id : le prochain login redemandera l'OTP).
	 */
	public void clear() {
		cookieSession = null;
		synoToken = null;
		cookieDid = null;
		if (file == null) {
			return;
		}
		try {
			Files.deleteIfExists(file);
		} catch (final IOException e) {
			throw new RuntimeException("Impossible de supprimer la session " + file, e);
		}
	}

	private void save() {
		if (file == null) {
			// copie de travail en mémoire
			return;
		}
		final Properties prop = new Properties();
		putProtected(prop, KEY_SESSION, cookieSession);
		putProtected(prop, KEY_TOKEN, synoToken);
		putProtected(prop, KEY_DID, cookieDid);

		try {
			PropertiesFiles.store(file, prop, "Session Synology Photos (chiffrée, propre à ce poste)");
		} catch (final IOException e) {
			throw new RuntimeException("Impossible d'écrire la session " + file, e);
		}
	}

	private static void putProtected(final Properties prop, final String key, final String value) {
		if (value != null) {
			prop.setProperty(key, CryptoUtils.encrypt(value));
		}
	}
}
