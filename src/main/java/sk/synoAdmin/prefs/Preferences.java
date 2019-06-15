package sk.synoAdmin.prefs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;
import java.util.stream.Collectors;

import sk.synoAdmin.UserException;
import sk.synoAdmin.engine.Group;

public class Preferences {

	public static final String PREF_FILE_NAME = ".synoRight";

	private final String rootPath;
	private final String url;
	private final String user;
	private final String password;
	private final List<Group> groups;

	private Preferences(final String rootPath, final String url, final String user, final String password, final List<Group> groups) {
		this.rootPath = rootPath;
		this.url = url;
		this.user = user;
		this.password = password;
		this.groups = Collections.unmodifiableList(groups);
	}

	/**
	 * Lit les préférences dans du fichier.
	 *
	 * @param dir le répertoire racine des photos du NAS
	 */
	public static Preferences fromFile(final String dir) {
		try (InputStream input = new FileInputStream(dir + "/" + PREF_FILE_NAME)) {

			final Properties prop = new Properties();
			prop.load(input);

			final String url = prop.getProperty("url");
			final String user = prop.getProperty("user");
			final String password = CryptoUtils.decrypt(prop.getProperty("password"));
			final String groupsString = prop.getProperty("groups");

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

			return new Preferences(dir, url, user, password, groups);

		} catch (final IOException e) {
			throw new RuntimeException("Impossible de lire les préférences", e);
		}
	}

	/**
	 * Construit un objet préférence à partir d'entrée utilisateur.
	 */
	public static Preferences fromPrompt(final String rootFolder) {
		final Scanner userInput = new Scanner(System.in);

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

		return new Preferences(rootFolder, url, user, password, groups);
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
	 *
	 * @param dir le répertoire racine des photos du NAS
	 */
	public void toFile() {
		final File prefFile = new File(rootPath + "/" + PREF_FILE_NAME);

		try (OutputStream output = new FileOutputStream(prefFile)) {

			final Properties prop = new Properties();

			prop.setProperty("url", url);
			prop.setProperty("user", user);
			prop.setProperty("password", CryptoUtils.encrypt(password));

			final String groupsString = groups.stream()
					.map(g -> g.getId() + ":" + g.getName())
					.collect(Collectors.joining(";"));

			prop.setProperty("groups", groupsString);

			// save properties to project root folder
			prop.store(output, null);

			Files.setAttribute(prefFile.toPath(), "dos:hidden", Boolean.TRUE, LinkOption.NOFOLLOW_LINKS);
		} catch (final IOException e) {
			throw new RuntimeException("Impossible d'écrire les préférences", e);
		}
	}

	public String getRootPath() {
		return rootPath;
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

}
