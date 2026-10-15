package sk.synoAdmin.prefs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;

import sk.synoAdmin.UserException;
import sk.synoAdmin.engine.Group;

public class Preferences {

	public static final String PREF_FILE_NAME = ".synoRight";
	public static final String CONFIG_FILENAME = "config" + PREF_FILE_NAME + ".properties";

	private final Path configDir;
	private final String nasFolder;
	private final String url;
	private final String user;
	private String password;
	private List<Group> groups;
	private final boolean verbose;
	private final Session session;
	// empreinte SHA-256 d'un certificat auto-signé du NAS à accepter (null : validation standard)
	private String certFingerprint;

	public Preferences(final Path configDir, final String nasFolder, final String url, final String user, final String password, final List<Group> groups, final boolean verbose) {
		this(configDir, nasFolder, url, user, password, groups, verbose, Session.load(configDir));
	}

	private Preferences(final Path configDir, final String nasFolder, final String url, final String user, final String password, final List<Group> groups, final boolean verbose, final Session session) {
		this.configDir = configDir;
		this.nasFolder = nasFolder;
		this.url = url;
		this.user = user;
		this.password = password;
		this.groups = List.copyOf(groups);
		this.verbose = verbose;
		this.session = session;
	}

	/**
	 * Répertoire de configuration par défaut : ~/.synoRight (à côté de la clé de chiffrement).
	 */
	public static Path defaultConfigDir() {
		return Paths.get(System.getProperty("user.home"), PREF_FILE_NAME);
	}

	/**
	 * Répertoire de configuration à utiliser : celui demandé explicitement, sinon le
	 * répertoire par défaut, sinon (compatibilité) le répertoire courant s'il contient
	 * une configuration.
	 */
	public static Path resolveConfigDir(final Path explicitDir) {
		if (explicitDir != null) {
			return explicitDir.toAbsolutePath().normalize();
		}
		final Path defaultDir = defaultConfigDir();
		final Path currentDir = Paths.get("").toAbsolutePath();
		if (!exists(defaultDir) && exists(currentDir)) {
			return currentDir;
		}
		return defaultDir;
	}

	public static boolean exists(final Path configDir) {
		return Files.isRegularFile(configDir.resolve(CONFIG_FILENAME));
	}

	/**
	 * Lit les préférences dans du fichier.
	 *
	 * @param configDir le répertoire contenant la configuration
	 */
	public static Preferences fromFile(final Path configDir) {
		final Path configFile = configDir.resolve(CONFIG_FILENAME);
		if (!Files.isRegularFile(configFile)) {
			throw new UserException("Fichier de configuration introuvable : " + configFile
					+ ". Lancer 'synoRight init' pour le créer (ou indiquer son répertoire avec -c).");
		}

		final Properties prop;
		try {
			prop = PropertiesFiles.load(configFile);
		} catch (final IOException e) {
			throw new RuntimeException("Impossible de lire les préférences", e);
		}

		final String folder = getRequiredProperty(prop, "folder");
		final String url = getRequiredProperty(prop, "url");
		final String user = getRequiredProperty(prop, "user");
		final String rawPassword = prop.getProperty("password");
		String password = null;
		if (rawPassword != null) {
			try {
				password = CryptoUtils.decrypt(rawPassword);
			} catch (final RuntimeException e) {
				System.out.println("Impossible de déchiffrer le mot de passe stocké (clé changée ?) : il sera demandé à la connexion."
						+ " Le mémoriser à nouveau avec 'synoRight password'.");
			}
		}
		final List<Group> groups = parseGroups(getRequiredProperty(prop, "groups"));
		final boolean verbose = Boolean.parseBoolean(prop.getProperty("verbose", "false").trim());

		final Session session = Session.load(configDir);
		final Preferences preferences = new Preferences(configDir, folder, url, user, password, groups, verbose, session);
		final String fingerprint = prop.getProperty("certFingerprint");
		preferences.certFingerprint = fingerprint == null || fingerprint.isBlank() ? null : fingerprint.trim();

		// migration de la session historiquement stockée dans la configuration
		final boolean sessionInConfig = session.importFromConfig(prop);

		// migration de l'ancien chiffrement (Blowfish, clé en dur) vers AES-GCM
		final boolean legacyPassword = password != null && !CryptoUtils.isCurrentFormat(rawPassword);

		if (sessionInConfig || legacyPassword) {
			preferences.save();
			if (sessionInConfig) {
				System.out.println("Session déplacée dans " + configDir.resolve(Session.FILENAME) + ".");
			}
			if (legacyPassword) {
				System.out.println("Mot de passe re-chiffré au nouveau format (clé : " + CryptoUtils.keyFile() + ").");
			}
		}

		return preferences;
	}

	private static String getRequiredProperty(final Properties prop, final String key) {
		final String value = prop.getProperty(key);
		if (value == null || value.isBlank()) {
			throw new UserException("Propriété '" + key + "' manquante dans la configuration");
		}
		return value.trim();
	}

	private static List<Group> parseGroups(final String groupsString) {
		final List<Group> groups = new ArrayList<>();

		for (final String groupString : groupsString.split(";")) {
			final String[] groupSplit = groupString.split(":");
			if (groupSplit.length != 2) {
				throw new UserException("Format des groupes incorrect dans la configuration (attendu : id:nom;id:nom)");
			}

			groups.add(new Group(groupSplit[0].trim(), groupSplit[1].trim()));
		}

		if (groups.isEmpty()) {
			throw new UserException("Aucun groupe de configuré !");
		}
		return groups;
	}

	/**
	 * Ecrit les préférences dans le fichier (le mot de passe chiffré, s'il est mémorisé).
	 */
	public void save() {
		final Properties prop = new Properties();

		prop.setProperty("folder", nasFolder);
		prop.setProperty("url", url);
		prop.setProperty("user", user);
		if (password != null) {
			prop.setProperty("password", CryptoUtils.encrypt(password));
		}
		prop.setProperty("groups", groups.stream()
				.map(g -> g.getId() + ":" + g.getName())
				.collect(Collectors.joining(";")));
		prop.setProperty("verbose", String.valueOf(verbose));
		if (certFingerprint != null) {
			prop.setProperty("certFingerprint", certFingerprint);
		}

		try {
			PropertiesFiles.store(configDir.resolve(CONFIG_FILENAME), prop,
					"Configuration synoRight (groups=id:nom;id:nom, verbose=true pour les temps de traitement)");
		} catch (final IOException e) {
			throw new RuntimeException("Impossible d'écrire les préférences", e);
		}
	}

	public Path getConfigDir() {
		return configDir;
	}

	public String getNasFolder() {
		return nasFolder;
	}

	public String getUrl() {
		return url;
	}

	public String getUser() {
		return user;
	}

	public String getPassword() {
		return password;
	}

	/**
	 * Change le mot de passe mémorisé (null pour l'oublier) ; appeler save() pour persister.
	 */
	public void setPassword(final String password) {
		this.password = password;
	}

	public List<Group> getGroups() {
		return Collections.unmodifiableList(groups);
	}

	/**
	 * Change les groupes gérés ; appeler save() pour persister.
	 */
	public void setGroups(final List<Group> groups) {
		if (groups.isEmpty()) {
			throw new UserException("Au moins un groupe doit être configuré.");
		}
		this.groups = List.copyOf(groups);
	}

	public String getCertFingerprint() {
		return certFingerprint;
	}

	/**
	 * Change le certificat auto-signé accepté (null : aucun) ; appeler save() pour persister.
	 */
	public void setCertFingerprint(final String certFingerprint) {
		this.certFingerprint = certFingerprint;
	}

	public boolean isVerbose() {
		return verbose;
	}

	public Session getSession() {
		return session;
	}

	public Group getGroupByName(final String name) {
		return groups.stream()
				.filter(g -> g.getName().equalsIgnoreCase(name))
				.findAny()
				.orElseThrow(() -> new UserException("Groupe '" + name + "' non configuré. Groupes disponibles : "
						+ groups.stream().map(Group::getName).collect(Collectors.joining(", "))));
	}

	public Optional<Group> getGroupById(final String id) {
		return groups.stream()
				.filter(g -> g.getId().equals(id))
				.findAny();
	}
}
