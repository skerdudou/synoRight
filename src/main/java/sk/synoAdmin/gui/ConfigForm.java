package sk.synoAdmin.gui;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import sk.synoAdmin.engine.Group;
import sk.synoAdmin.menu.MenuEntry;
import sk.synoAdmin.menu.MenuTarget;
import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.prefs.Session;
import sk.synoAdmin.synology.ConnectionCheck;

/**
 * État du formulaire de configuration (sans Swing) : valeurs saisies, test de connexion, validation,
 * modifications non enregistrées.
 */
final class ConfigForm {

	static final String CONNECTION_REQUIRED = "Tester la connexion au NAS.";
	static final String FOLDER_INVALID = "Dossier des photos introuvable ou inaccessible.";
	static final String GROUPS_REQUIRED = "Choisir au moins un groupe.";

	/** Valeurs comparées pour détecter les modifications non enregistrées. */
	private record State(String url, String user, String password, boolean storePassword, String folder,
			List<String> groups, List<MenuEntry> menuEntries, MenuTarget menuTarget, String fingerprint) {
	}

	String url = "";
	String user = "";
	String password;
	boolean storePassword = true;
	String folder = "";
	List<Group> groups = List.of();
	List<MenuEntry> menuEntries = MenuEntry.defaults();
	/** null : choix automatique selon la version de Windows. */
	MenuTarget menuTarget;
	String fingerprint;

	private boolean existing;
	private State snapshot;
	private ConnectionCheck.Result result;
	private String connectedFor;
	private boolean everConnected;

	private ConfigForm() {
		// voir from
	}

	/**
	 * Formulaire initialisé avec la configuration existante, ou vide (nouvelle configuration) si null.
	 */
	static ConfigForm from(final Preferences pref) {
		final ConfigForm form = new ConfigForm();
		if (pref != null) {
			form.url = pref.getUrl();
			form.user = pref.getUser();
			form.password = pref.getPassword();
			form.storePassword = pref.getPassword() != null;
			form.folder = pref.getNasFolder();
			form.groups = List.copyOf(pref.getGroups());
			form.menuEntries = List.copyOf(pref.getMenuEntries());
			form.menuTarget = pref.getMenuTarget();
			form.fingerprint = pref.getCertFingerprint();
			form.existing = true;
		}
		form.snapshot = form.state();
		return form;
	}

	/**
	 * Formulaire pour la configuration qui vient d'être enregistrée : nouvel instantané, état de connexion conservé
	 * (la session de travail, déjà enregistrée, n'est pas reprise).
	 */
	ConfigForm afterSave(final Preferences saved) {
		final ConfigForm form = from(saved);
		form.everConnected = everConnected;
		return form;
	}

	/** true si une configuration existait (ou a été enregistrée). */
	boolean isExisting() {
		return existing;
	}

	/** true si le mot de passe de la configuration existante est mémorisé. */
	boolean hasStoredPassword() {
		return existing && snapshot.password() != null;
	}

	/**
	 * Mot de passe saisi ; vide sur une configuration existante : conserve le mot de passe actuel.
	 */
	void setPasswordInput(final String typed) {
		if (typed == null || typed.isEmpty()) {
			password = existing ? snapshot.password() : null;
		} else {
			password = typed;
		}
	}

	/** Même NAS et même compte que la configuration existante (le device id OTP est conservé). */
	boolean sameAccount() {
		return existing && normalizedUrl().equals(ConnectionCheck.normalizeUrl(snapshot.url())) && Objects.equals(user, snapshot.user());
	}

	/** Mot de passe inchangé venant de la configuration : pas de nouvelle saisie proposée en cas d'échec. */
	boolean passwordFromConfig() {
		return sameAccount() && password != null && password.equals(snapshot.password());
	}

	/** Mémorise le résultat d'un test de connexion réussi sur les valeurs courantes. */
	void connected(final ConnectionCheck.Result connection) {
		result = connection;
		password = connection.password();
		fingerprint = connection.fingerprint();
		connectedFor = connectionKey();
		everConnected = true;
	}

	/** true si une connexion a réussi au moins une fois depuis l'ouverture. */
	boolean hasConnected() {
		return everConnected;
	}

	/** true si les paramètres de connexion diffèrent de la configuration existante (toujours vrai sans configuration). */
	boolean connectionChanged() {
		return !existing
				|| !normalizedUrl().equals(snapshot.url())
				|| !Objects.equals(user, snapshot.user())
				|| !Objects.equals(password, snapshot.password())
				|| !Objects.equals(fingerprint, snapshot.fingerprint());
	}

	/** true si les paramètres de connexion n'ont pas besoin d'un (nouveau) test. */
	boolean connectionVerified() {
		return !connectionChanged() || connectionKey().equals(connectedFor);
	}

	/**
	 * Problèmes empêchant l'enregistrement, vide si aucun.
	 */
	List<String> validate() {
		final List<String> problems = new ArrayList<>();
		if (!connectionVerified()) {
			problems.add(CONNECTION_REQUIRED);
		}
		if (!isAccessibleFolder(folder)) {
			problems.add(FOLDER_INVALID);
		}
		if (groups.isEmpty()) {
			problems.add(GROUPS_REQUIRED);
		}
		return problems;
	}

	static boolean isAccessibleFolder(final String folder) {
		if (folder == null || folder.isBlank()) {
			return false;
		}
		try {
			return Files.isDirectory(Paths.get(folder));
		} catch (final InvalidPathException e) {
			return false;
		}
	}

	/** Configuration à enregistrer. */
	Preferences toPreferences(final Path dir, final boolean verbose) {
		final Preferences pref = new Preferences(dir, folder, normalizedUrl(), user, storePassword ? password : null, groups, verbose);
		pref.setCertFingerprint(fingerprint);
		pref.setMenuEntries(menuEntries);
		pref.setMenuTarget(menuTarget);
		return pref;
	}

	/** Session de travail du dernier test de connexion, s'il porte sur les valeurs courantes (null sinon). */
	Session workSession() {
		return result != null && connectionKey().equals(connectedFor) ? result.workSession() : null;
	}

	/** true si des modifications ne sont pas enregistrées. */
	boolean isDirty() {
		return !state().equals(snapshot);
	}

	private String normalizedUrl() {
		return ConnectionCheck.normalizeUrl(Objects.toString(url, ""));
	}

	private String connectionKey() {
		return normalizedUrl() + '\0' + user + '\0' + password + '\0' + fingerprint;
	}

	private State state() {
		return new State(normalizedUrl(), user, password, storePassword, folder,
				// ordre indifférent : les cases suivent l'ordre du NAS, la configuration le sien
				groups.stream().map(g -> g.getId() + ":" + g.getName()).sorted().toList(),
				List.copyOf(menuEntries), menuTarget, fingerprint);
	}
}
