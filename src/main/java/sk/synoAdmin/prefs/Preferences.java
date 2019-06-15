package sk.synoAdmin.prefs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Properties;
import java.util.Scanner;

public class Preferences {

	public static final String PREF_FILE_NAME = ".synoRight";

	private final String rootPath;
	private final String url;
	private final String user;
	private final String password;

	private Preferences(final String rootPath, final String url, final String user, final String password) {
		this.rootPath = rootPath;
		this.url = url;
		this.user = user;
		this.password = password;
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

			return new Preferences(dir, url, user, password);

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

		userInput.close();

		return new Preferences(rootFolder, url, user, password);
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

}
