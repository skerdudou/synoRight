package sk.synoAdmin.gui;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import sk.synoAdmin.engine.Group;
import sk.synoAdmin.engine.GroupChecks;
import sk.synoAdmin.i18n.Messages;
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

	/** Section de la fenêtre où corriger un problème de validation. */
	enum Section {
		CONNECTION, FOLDER, GROUPS
	}

	/** Problème empêchant l'enregistrement : section à afficher et message (déjà traduit). */
	record Problem(Section section, String message) {
	}

	/** Valeurs comparées pour détecter les modifications non enregistrées. */
	private record State(String url, String user, String password, boolean storePassword, String folder,
			List<String> groups, List<MenuEntry> modernMenuEntries, List<MenuEntry> classicMenuEntries, MenuTarget menuTarget,
			String fingerprint, String language, boolean updateCheck) {
	}

	String url = "";
	String user = "";
	String password;
	boolean storePassword = true;
	String folder = "";
	List<Group> groups = List.of();
	List<MenuEntry> modernMenuEntries = MenuEntry.defaults();
	List<MenuEntry> classicMenuEntries = MenuEntry.defaults();
	/** null : choix automatique selon la version de Windows. */
	MenuTarget menuTarget;
	String fingerprint;
	/** Langue de l'interface : "auto" ou un code (en, fr, de, es, it). */
	String language = Preferences.LANGUAGE_AUTO;
	/** Recherche d'une nouvelle version à l'ouverture de la fenêtre. */
	boolean updateCheck = true;

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
			form.modernMenuEntries = List.copyOf(pref.getMenuEntries(true));
			form.classicMenuEntries = List.copyOf(pref.getMenuEntries(false));
			form.menuTarget = pref.getMenuTarget();
			form.fingerprint = pref.getCertFingerprint();
			form.language = pref.getLanguageSetting();
			form.updateCheck = pref.isUpdateCheck();
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
	List<Problem> validate() {
		final List<Problem> problems = new ArrayList<>();
		if (!connectionVerified()) {
			problems.add(new Problem(Section.CONNECTION, Messages.get("gui.config.problem.connectionRequired")));
		}
		if (!isAccessibleFolder(folder)) {
			problems.add(new Problem(Section.FOLDER, Messages.get("gui.config.problem.folderInvalid")));
		}
		if (groups.isEmpty()) {
			problems.add(new Problem(Section.GROUPS, Messages.get("gui.config.problem.groupsRequired")));
		}
		for (final Group group : groups) {
			final String reason = GroupChecks.invalidShortName(group.getName(), groups, group.getId());
			if (reason != null) {
				problems.add(new Problem(Section.GROUPS, Messages.get("gui.config.problem.shortName", group.getId(), group.getName(), reason)));
			}
		}
		return problems;
	}

	/**
	 * Groupes déjà gérés dont le nom court change : leurs fichiers @ancien.synoRight ne seront plus reconnus.
	 *
	 * @return une note par groupe renommé, vide si aucun
	 */
	List<String> renamedGroupNotes() {
		final List<String> notes = new ArrayList<>();
		if (!existing) {
			return notes;
		}
		for (final String before : snapshot.groups()) {
			final String id = before.substring(0, before.indexOf(':'));
			final String oldName = before.substring(before.indexOf(':') + 1);
			groups.stream()
					// casse seule : les fichiers existants restent trouvés (partage Windows insensible à la casse)
					.filter(g -> g.getId().equals(id) && !g.getName().equalsIgnoreCase(oldName))
					.findAny()
					.ifPresent(g -> notes.add(Messages.get("gui.config.note.renamed", oldName, g.getName())));
		}
		return notes;
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
		pref.setMenuEntries(true, modernMenuEntries);
		pref.setMenuEntries(false, classicMenuEntries);
		pref.setMenuTarget(menuTarget);
		pref.setLanguageSetting(language);
		pref.setUpdateCheck(updateCheck);
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
				List.copyOf(modernMenuEntries), List.copyOf(classicMenuEntries), menuTarget, fingerprint, language, updateCheck);
	}
}
