package sk.synoAdmin.prefs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;

import sk.synoAdmin.ConsoleUtil;
import sk.synoAdmin.UserException;
import sk.synoAdmin.engine.Group;

public class Preferences {

	public static final String PREF_FILE_NAME = ".synoRight";
	public static final String CONFIG_FILENAME = "config" + PREF_FILE_NAME + ".properties";

	private final String configPath;
	private final String nasFolder;
	private final String url;
	private final String user;
	private final String password;
	private final List<Group> groups;
	private String cookieSession;
	private String synoToken;
	private String cookieDid; // device id for OTP

	private Preferences(final String configPath, final String nasFolder, final String url, final String user, final String password, final List<Group> groups, final String cookieSession, final String synoToken, final String cookieDid) {
		this.configPath = configPath;
		this.nasFolder = nasFolder;
		this.url = url;
		this.user = user;
		this.password = password;
		this.groups = Collections.unmodifiableList(groups);
		this.cookieSession = cookieSession;
		this.synoToken = synoToken;
		this.cookieDid = cookieDid;
	}

	/**
	 * Lit les préférences dans du fichier.
	 *
	 * @param configPath le répertoire contenant la configuration
	 */
	public static Preferences fromFile(final String configPath) {
		final File configFile = new File(configPath, CONFIG_FILENAME);
		if (!configFile.isFile()) {
			throw new UserException("Fichier de configuration introuvable : " + configFile.getPath()
					+ ". Lancer l'action INIT pour le créer.");
		}

		try (InputStream input = new FileInputStream(configFile)) {

			final Properties prop = new Properties();
			prop.load(input);

			final String folder = getRequiredProperty(prop, "folder");
			final String url = getRequiredProperty(prop, "url");
			final String user = getRequiredProperty(prop, "user");
			final String password = CryptoUtils.decrypt(prop.getProperty("password"));
			final String groupsString = getRequiredProperty(prop, "groups");
			final String cookieSession = prop.getProperty("cookieSession");
			final String synoToken = prop.getProperty("synoToken");
			final String cookieDid = prop.getProperty("cookieDid");

			final List<Group> groups = new ArrayList<>();

			for (final String groupString : groupsString.split(";")) {
				final String[] groupSplit = groupString.split(":");
				if (groupSplit.length != 2) {
					throw new UserException("Format des groupes incorrect dans la configuration (attendu : id:nom;id:nom)");
				}

				groups.add(new Group(groupSplit[0], groupSplit[1]));
			}

			if (groups.isEmpty()) {
				throw new UserException("Aucun groupe de configuré !");
			}

			return new Preferences(configPath, folder, url, user, password, groups, cookieSession, synoToken, cookieDid);
		} catch (final IOException e) {
			throw new RuntimeException("Impossible de lire les préférences", e);
		}
	}

	private static String getRequiredProperty(final Properties prop, final String key) {
		final String value = prop.getProperty(key);
		if (value == null || value.isBlank()) {
			throw new UserException("Propriété '" + key + "' manquante dans la configuration");
		}
		return value;
	}

	/**
	 * Construit un objet préférence à partir d'entrée utilisateur.
	 */
	public static Preferences fromPrompt(final String configFolder) {
		final String nasFolder = ConsoleUtil.promptLine("Répertoire des photos sur le NAS :");
		final String url = ConsoleUtil.promptLine("URL d'accès à Synology Photos :");
		final String user = ConsoleUtil.promptLine("User :");
		final String password = ConsoleUtil.promptSecret("Password :");

		System.out.println("Configuration des groupes.");
		int numGrp = 1;
		final List<Group> groups = new ArrayList<>();
		do {
			final String groupId = ConsoleUtil.promptLine("Identifiant du groupe " + numGrp + " :");
			final String groupName = ConsoleUtil.promptLine("Nom du groupe " + numGrp + " :");

			groups.add(new Group(groupId, groupName));

			numGrp++;
		} while (ConsoleUtil.promptBool("Ajouter un nouveau groupe ?"));

		return new Preferences(configFolder, nasFolder, url, user, password, groups, null, null, null);
	}

	/**
	 * Ecrit les préférences dans le fichier.
	 */
	public void toFile() {
		final File prefFile = new File(configPath, CONFIG_FILENAME);

		try (OutputStream output = new FileOutputStream(prefFile)) {

			final Properties prop = new Properties();

			prop.setProperty("folder", nasFolder);
			prop.setProperty("url", url);
			prop.setProperty("user", user);
			prop.setProperty("password", CryptoUtils.encrypt(password));

			final String groupsString = groups.stream()
					.map(g -> g.getId() + ":" + g.getName())
					.collect(Collectors.joining(";"));

			prop.setProperty("groups", groupsString);

			prop.store(output, null);
		} catch (final IOException e) {
			throw new RuntimeException("Impossible d'écrire les préférences", e);
		}
	}

	public String getConfigPath() {
		return configPath;
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

	public List<Group> getGroups() {
		return groups;
	}

	public Group getGroupByName(final String name) {
		return groups.stream()
				.filter(g -> g.getName().equalsIgnoreCase(name))
				.findAny()
				.orElseThrow(() -> new UserException("Impossible de trouver le groupe " + name));
	}

	public Optional<Group> getGroupById(final String id) {
		return groups.stream()
				.filter(g -> g.getId().equals(id))
				.findAny();
	}

	public String getCookieSession() {
		return cookieSession;
	}

	public void setCookieSession(final String cookieSession) {
		this.cookieSession = cookieSession;
		setValueToConfigFile("cookieSession", cookieSession);
	}

	public String getSynoToken() {
		return synoToken;
	}

	public void setSynoToken(final String synoToken) {
		this.synoToken = synoToken;
		setValueToConfigFile("synoToken", synoToken);
	}

	public String getCookieDid() {
		return cookieDid;
	}

	public void setCookieDid(final String cookieDid) {
		this.cookieDid = cookieDid;
		setValueToConfigFile("cookieDid", cookieDid);
	}

	private void setValueToConfigFile(final String key, final String value) {
		final File prefFile = new File(configPath, CONFIG_FILENAME);

		final Properties prop = new Properties();
		if (prefFile.isFile()) {
			try (InputStream input = new FileInputStream(prefFile)) {
				prop.load(input);
			} catch (final IOException e) {
				throw new RuntimeException("Impossible de lire les préférences", e);
			}
		}

		try (OutputStream output = new FileOutputStream(prefFile)) {
			if (value == null) {
				prop.remove(key);
			} else {
				prop.setProperty(key, value);
			}
			prop.store(output, null);
		} catch (final IOException e) {
			throw new RuntimeException("Impossible d'écrire les préférences", e);
		}
	}
}
