package sk.synoAdmin.prefs;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

import sk.synoAdmin.UserException;
import sk.synoAdmin.engine.Group;

public class Preferences {

	public static final String PREF_FILE_NAME = ".synoRight";
	public static final String CONFIG_FILENAME = "/config" + PREF_FILE_NAME + ".properties";

	private final String configPath;
	private final String nasFolder;
	private final String url;
	private final String user;
	private final String password;
	private final List<Group> groups;
	private String cookieSession;
	private String synoToken;
	private String cookieDid; // device id for OTP

	private Preferences(final String configPath, final String nasFolder, final String url, final String user, final String password, final List<Group> groups, String cookieSession, String synoToken, String cookieDid) {
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
		try (InputStream input = new FileInputStream(configPath + "/" + CONFIG_FILENAME)) {

			final Properties prop = new Properties();
			prop.load(input);

			final String folder = prop.getProperty("folder");
			final String url = prop.getProperty("url");
			final String user = prop.getProperty("user");
			final String password = CryptoUtils.decrypt(prop.getProperty("password"));
			final String groupsString = prop.getProperty("groups");
			final String cookieSession = prop.getProperty("cookieSession");
			final String synoToken = prop.getProperty("synoToken");
			final String cookieDid = prop.getProperty("cookieDid");

			final List<Group> groups = new ArrayList<>();
			final String[] groupsSplit = groupsString.split(";");

			if (groupsSplit.length == 0) {
				throw new UserException("Aucun groupe de configuré !");
			}

			for (final String groupString : groupsSplit) {
				final String[] groupSplit = groupString.split(":");
				if (groupSplit.length != 2) {
					throw new UserException("Format des groupes incorrect dans la configuration");
				}

				groups.add(new Group(groupSplit[0], groupSplit[1]));
			}

			return new Preferences(configPath, folder, url, user, password, groups, cookieSession, synoToken, cookieDid);
		} catch (final IOException e) {
			throw new RuntimeException("Impossible de lire les préférences", e);
		}
	}

	/**
	 * Construit un objet préférence à partir d'entrée utilisateur.
	 */
	public static Preferences fromPrompt(final String configFolder) {
		final Scanner userInput = new Scanner(System.in);

		System.out.println("URL des photos sur le NAS :");
		final String nasFolder = userInput.next();

		System.out.println("URL d'accès à PhotoStation :");
		final String url = userInput.next();

		System.out.println("User :");
		final String user = userInput.next();

		System.out.println("Password :");
		final String password = userInput.next();

		System.out.println("Configuration des groupes.");
		int numGrp = 1;
		final List<Group> groups = new ArrayList<>();
		do {
			System.out.println("Identifiant du groupe " + numGrp + " :");
			final String groupId = userInput.next();

			System.out.println("Nom du groupe " + numGrp + " :");
			final String groupName = userInput.next();

			groups.add(new Group(groupId, groupName));

			System.out.println("Ajouter un nouveau groupe ?");
			numGrp++;
		} while (scanBool(userInput));

		userInput.close();

		return new Preferences(configFolder, nasFolder, url, user, password, groups, null, null, null);
	}

	private static boolean scanBool(final Scanner userInput) {
		boolean incorrect;
		boolean isYes;
		do {
			final String rep = userInput.next();

			isYes = rep.equalsIgnoreCase("oui") ||
					rep.equalsIgnoreCase("o") ||
					rep.equalsIgnoreCase("yes") ||
					rep.equalsIgnoreCase("y");

			final boolean isNo = rep.equalsIgnoreCase("non") ||
					rep.equalsIgnoreCase("n") ||
					rep.equalsIgnoreCase("no");

			incorrect = !isYes && !isNo;
			if (incorrect) {
				System.out.println("Répondre par oui ou non");
			}
		} while (incorrect);

		return isYes;
	}

	/**
	 * Ecrit les préférences dans le fichier.
	 */
	public void toFile() {
		final File prefFile = new File(configPath + "/" + CONFIG_FILENAME);

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

			// save properties to project root folder
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

	public void setCookieSession(String cookieSession) {
		this.cookieSession = cookieSession;
		setValueToConfigFile("cookieSession", cookieSession);
	}

	public String getSynoToken() {
		return synoToken;
	}

	public void setSynoToken(String synoToken) {
		this.synoToken = synoToken;
		setValueToConfigFile("synoToken", synoToken);
	}

	public String getCookieDid() {
		return cookieDid;
	}

	public void setCookieDid(String cookieDid) {
		this.cookieDid = cookieDid;
		setValueToConfigFile("cookieDid", cookieDid);
	}

	private void setValueToConfigFile(String key, String value) {
		final File prefFile = new File(configPath + "/" + CONFIG_FILENAME);

		final Properties prop;
		try (InputStream input = new FileInputStream(prefFile)) {
			prop = new Properties();
			prop.load(input);
		} catch (final IOException e) {
			throw new RuntimeException("Impossible de lire les préférences", e);
		}


		try (OutputStream output = new FileOutputStream(prefFile)) {
			prop.setProperty(key, value);
			prop.store(output, null);
		} catch (final IOException e) {
			throw new RuntimeException("Impossible d'écrire les préférences", e);
		}
	}
}
