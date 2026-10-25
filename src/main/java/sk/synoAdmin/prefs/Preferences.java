package sk.synoAdmin.prefs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;

import sk.synoAdmin.UserException;
import sk.synoAdmin.engine.Group;
import sk.synoAdmin.i18n.Language;
import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.menu.MenuEntry;
import sk.synoAdmin.menu.MenuTarget;

public class Preferences {

	public static final String PREF_FILE_NAME = ".synoRight";
	public static final String CONFIG_FILENAME = "config" + PREF_FILE_NAME + ".properties";

	public static final String LANGUAGE_AUTO = "auto";

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
	// entrées du menu contextuel de l'Explorateur, propres à chaque menu
	private List<MenuEntry> modernMenuEntries = MenuEntry.defaults();
	private List<MenuEntry> classicMenuEntries = MenuEntry.defaults();
	// menu(s) à installer (null : automatique selon la version de Windows)
	private MenuTarget menuTarget;
	// langue de l'interface : "auto" (langue du système) ou un code de langue (en, fr, de, es, it)
	private String languageSetting = LANGUAGE_AUTO;
	// recherche d'une nouvelle version à l'ouverture de la fenêtre de configuration
	private boolean updateCheck = true;

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
			throw new UserException(Messages.get("prefs.config.notFound", configFile));
		}

		final Properties prop;
		try {
			prop = PropertiesFiles.load(configFile);
		} catch (final IOException e) {
			throw new RuntimeException(Messages.get("prefs.config.readError"), e);
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
				System.out.println(Messages.get("prefs.password.undecryptable"));
			}
		}
		final List<Group> groups = parseGroups(getRequiredProperty(prop, "groups"));
		final boolean verbose = Boolean.parseBoolean(prop.getProperty("verbose", "false").trim());

		final Session session = Session.load(configDir);
		final Preferences preferences = new Preferences(configDir, folder, url, user, password, groups, verbose, session);
		final String fingerprint = prop.getProperty("certFingerprint");
		preferences.certFingerprint = fingerprint == null || fingerprint.isBlank() ? null : fingerprint.trim();
		// menuEntries : ancienne clé commune aux deux menus, valeur par défaut des clés propres à chaque menu
		final String common = prop.getProperty("menuEntries");
		final String modern = prop.getProperty("menuEntries.modern", common);
		final String classic = prop.getProperty("menuEntries.classic", common);
		if (modern != null) {
			preferences.modernMenuEntries = parseMenuEntries(modern);
		}
		if (classic != null) {
			preferences.classicMenuEntries = parseMenuEntries(classic);
		}
		final String target = prop.getProperty("menuTarget");
		preferences.menuTarget = target == null ? null : MenuTarget.fromId(target).orElse(null);
		preferences.languageSetting = normalizeLanguageSetting(prop.getProperty("language"));
		preferences.updateCheck = Boolean.parseBoolean(prop.getProperty("updateCheck", "true").trim());

		// migration de la session historiquement stockée dans la configuration
		final boolean sessionInConfig = session.importFromConfig(prop);

		// migration de l'ancien chiffrement (Blowfish, clé en dur) vers AES-GCM
		final boolean legacyPassword = password != null && !CryptoUtils.isCurrentFormat(rawPassword);

		if (sessionInConfig || legacyPassword) {
			preferences.save();
			if (sessionInConfig) {
				System.out.println(Messages.get("prefs.session.moved", configDir.resolve(Session.FILENAME)));
			}
			if (legacyPassword) {
				System.out.println(Messages.get("prefs.password.reencrypted", CryptoUtils.keyFile()));
			}
		}

		return preferences;
	}

	// "auto" si absent ou inconnu, sinon le code de langue en minuscules
	private static String normalizeLanguageSetting(final String value) {
		return Language.fromCode(value).map(Language::code).orElse(LANGUAGE_AUTO);
	}

	/**
	 * Lit seulement le réglage de langue de la configuration, sans rien afficher : "auto" sur toute erreur.
	 * Utilisé au démarrage, avant le traitement de la ligne de commande.
	 */
	public static String readLanguageSetting(final Path configDir) {
		try {
			final Path configFile = configDir.resolve(CONFIG_FILENAME);
			if (!Files.isRegularFile(configFile)) {
				return LANGUAGE_AUTO;
			}
			return normalizeLanguageSetting(PropertiesFiles.load(configFile).getProperty("language"));
		} catch (final IOException | RuntimeException e) {
			return LANGUAGE_AUTO;
		}
	}

	private static String getRequiredProperty(final Properties prop, final String key) {
		final String value = prop.getProperty(key);
		if (value == null || value.isBlank()) {
			throw new UserException(Messages.get("prefs.config.missingProperty", key));
		}
		return value.trim();
	}

	// ids séparés par des virgules ; ids inconnus ignorés (configuration écrite par une version plus récente)
	private static List<MenuEntry> parseMenuEntries(final String entries) {
		return Arrays.stream(entries.split(","))
				.filter(s -> !s.isBlank())
				.map(MenuEntry::fromId)
				.flatMap(Optional::stream)
				.distinct()
				.toList();
	}

	private static String formatMenuEntries(final List<MenuEntry> entries) {
		return entries.stream().map(MenuEntry::id).collect(Collectors.joining(","));
	}

	private static List<Group> parseGroups(final String groupsString) {
		final List<Group> groups = new ArrayList<>();

		for (final String groupString : groupsString.split(";")) {
			final String[] groupSplit = groupString.split(":");
			if (groupSplit.length != 2) {
				throw new UserException(Messages.get("prefs.config.badGroups"));
			}

			groups.add(new Group(groupSplit[0].trim(), groupSplit[1].trim()));
		}

		if (groups.isEmpty()) {
			throw new UserException(Messages.get("prefs.config.noGroup"));
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
		prop.setProperty("menuEntries.modern", formatMenuEntries(modernMenuEntries));
		prop.setProperty("menuEntries.classic", formatMenuEntries(classicMenuEntries));
		if (menuTarget != null) {
			prop.setProperty("menuTarget", menuTarget.id());
		}
		prop.setProperty("language", languageSetting);
		prop.setProperty("updateCheck", String.valueOf(updateCheck));

		try {
			PropertiesFiles.store(configDir.resolve(CONFIG_FILENAME), prop,
					"Configuration synoRight (groups=id:nom;id:nom, verbose=true pour les temps de traitement,"
						+ " menuEntries.modern / menuEntries.classic=rights,status,... menuTarget=modern|classic|all,"
							+ " language=auto|en|fr|de|es|it,"
							+ " updateCheck=false pour ne pas rechercher de nouvelle version)");
		} catch (final IOException e) {
			throw new RuntimeException(Messages.get("prefs.config.writeError"), e);
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
			throw new UserException(Messages.get("prefs.groups.atLeastOne"));
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

	/**
	 * Entrées d'un menu contextuel.
	 *
	 * @param modern true : menu Windows 11, false : menu classique
	 */
	public List<MenuEntry> getMenuEntries(final boolean modern) {
		return modern ? modernMenuEntries : classicMenuEntries;
	}

	/**
	 * Change les entrées d'un menu contextuel ; appeler save() pour persister.
	 *
	 * @param modern true : menu Windows 11, false : menu classique
	 */
	public void setMenuEntries(final boolean modern, final List<MenuEntry> entries) {
		if (modern) {
			modernMenuEntries = List.copyOf(entries);
		} else {
			classicMenuEntries = List.copyOf(entries);
		}
	}

	/**
	 * Menu(s) à installer ; null pour le choix automatique.
	 */
	public MenuTarget getMenuTarget() {
		return menuTarget;
	}

	/**
	 * Change le(s) menu(s) à installer (null : automatique) ; appeler save() pour persister.
	 */
	public void setMenuTarget(final MenuTarget menuTarget) {
		this.menuTarget = menuTarget;
	}

	/**
	 * Réglage de langue : "auto" (langue du système) ou un code de langue.
	 */
	public String getLanguageSetting() {
		return languageSetting;
	}

	/**
	 * Change le réglage de langue ("auto" ou un code ; toute autre valeur donne "auto") ; appeler save() pour persister.
	 */
	public void setLanguageSetting(final String languageSetting) {
		this.languageSetting = normalizeLanguageSetting(languageSetting);
	}

	/**
	 * true si une nouvelle version est recherchée à l'ouverture de la fenêtre de configuration.
	 */
	public boolean isUpdateCheck() {
		return updateCheck;
	}

	/**
	 * Active ou non la recherche d'une nouvelle version à l'ouverture ; appeler save() pour persister.
	 */
	public void setUpdateCheck(final boolean updateCheck) {
		this.updateCheck = updateCheck;
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
				.orElseThrow(() -> new UserException(Messages.get("prefs.group.unknown", name, groups.stream().map(Group::getName).collect(Collectors.joining(", ")))));
	}

	public Optional<Group> getGroupById(final String id) {
		return groups.stream()
				.filter(g -> g.getId().equals(id))
				.findAny();
	}
}
