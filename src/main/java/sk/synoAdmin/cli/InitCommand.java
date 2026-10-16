package sk.synoAdmin.cli;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.X509Certificate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

import javax.net.ssl.SSLHandshakeException;

import picocli.CommandLine.Command;
import sk.synoAdmin.ConsoleUtil;
import sk.synoAdmin.UserException;
import sk.synoAdmin.engine.Group;
import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.prefs.Session;
import sk.synoAdmin.synology.SynoApiClient;
import sk.synoAdmin.synology.SynoGroup;
import sk.synoAdmin.synology.TrustedCertificate;

/**
 * Assistant de configuration : connexion au NAS (validée avant d'aller plus loin),
 * dossier photos, choix des groupes parmi ceux du NAS, puis menu contextuel.
 * Relancé sur une configuration existante, il propose les valeurs actuelles.
 */
@Command(name = "init", description = "Assistant de configuration : connexion au NAS, dossier photos, groupes et menu contextuel.")
final class InitCommand extends AbstractCommand {

	@Override
	void execute() {
		final Path dir = configDir();
		final Preferences existing = loadExisting(dir);

		if (existing != null) {
			System.out.println("Modification de la configuration " + dir.resolve(Preferences.CONFIG_FILENAME));
			System.out.println("(Entrée seule conserve la valeur entre crochets.)");
		} else {
			System.out.println("Création de la configuration dans " + dir);
		}

		// 1. connexion, validée avant de poser les autres questions
		final Session session = Session.load(dir);
		Session workSession;
		String url = existing == null ? null : existing.getUrl();
		String user = existing == null ? null : existing.getUser();
		final String[] password = new String[1];
		String fingerprint = existing == null ? null : existing.getCertFingerprint();
		SynoApiClient client;
		while (true) {
			url = normalizeUrl(ConsoleUtil.promptLine("URL de DSM (ex : https://nas.example.com:5001) :", url));
			user = ConsoleUtil.promptLine("Compte (administrateur de Synology Photos) :", user);

			final boolean sameAccount = existing != null && url.equals(normalizeUrl(existing.getUrl())) && user.equals(existing.getUser());
			final boolean keepPassword;
			if (sameAccount && existing.getPassword() != null) {
				password[0] = ConsoleUtil.promptSecretOrKeep("Mot de passe (Entrée pour conserver l'actuel) :");
				keepPassword = password[0] == null;
				if (keepPassword) {
					password[0] = existing.getPassword();
				}
			} else {
				password[0] = ConsoleUtil.promptSecret("Mot de passe :");
				keepPassword = false;
			}

			// session de travail en mémoire : la session mémorisée n'est remplacée qu'à l'enregistrement ;
			// même compte : le device id évite de redemander l'OTP
			workSession = session.transientCopy(sameAccount);

			boolean connected = false;
			while (true) {
				// mot de passe corrigé pendant le login : c'est lui qui sera mémorisé
				client = new SynoApiClient(url, user, password[0], keepPassword, workSession, fingerprint, newPassword -> password[0] = newPassword);
				try {
					client.login();
					connected = true;
				} catch (final UserException e) {
					// certificat auto-signé : proposer de lui faire confiance, puis réessayer avec les mêmes identifiants
					final String trusted = isCertificateError(e) ? promptTrustCertificate(url, fingerprint) : null;
					if (trusted != null) {
						fingerprint = trusted;
						continue;
					}
					System.out.println(e.getMessage());
				}
				break;
			}
			if (connected) {
				System.out.println("Connexion OK.");
				break;
			}
			if (!ConsoleUtil.promptBool("Réessayer ?")) {
				throw new UserException("Configuration abandonnée, rien n'a été enregistré.");
			}
		}

		// 2. dossier photos
		final String folder = promptFolder(existing == null ? null : existing.getNasFolder());

		// 3. groupes, choisis parmi ceux du NAS
		final List<Group> groups = promptGroups(client, existing);

		// mot de passe volontairement non mémorisé (password --forget) : on redemande
		final boolean storePassword = existing == null || existing.getPassword() != null
				|| ConsoleUtil.promptBool("Mémoriser le mot de passe dans la configuration ?");

		session.replaceWith(workSession);
		final Preferences pref = new Preferences(dir, folder, url, user, storePassword ? password[0] : null, groups, existing != null && existing.isVerbose());
		pref.setCertFingerprint(fingerprint);
		pref.save();
		System.out.println("Configuration enregistrée : " + dir.resolve(Preferences.CONFIG_FILENAME));

		// 4. menu contextuel
		if (System.getProperty("os.name", "").toLowerCase().startsWith("windows")
				&& ConsoleUtil.promptBool("Installer (ou mettre à jour) le menu contextuel de l'Explorateur ?")) {
			try {
				ConfigCommands.installMenu(pref, dir);
			} catch (final UserException e) {
				System.out.println("Menu non installé : " + e.getMessage());
			}
		}

		System.out.println();
		System.out.println("Terminé. Pour commencer : 'synoRight status' affiche l'état des droits, 'synoRight --help' liste les commandes.");
	}

	static boolean isCertificateError(final Throwable e) {
		for (Throwable cause = e; cause != null; cause = cause.getCause()) {
			if (cause instanceof SSLHandshakeException) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Affiche le certificat présenté par le NAS et demande si on lui fait confiance.
	 *
	 * @return son empreinte si l'utilisateur l'accepte, null sinon
	 */
	private static String promptTrustCertificate(final String url, final String currentFingerprint) {
		final X509Certificate certificate;
		try {
			certificate = TrustedCertificate.fetchServerCertificate(url);
		} catch (final UserException e) {
			System.out.println(e.getMessage());
			return null;
		}
		final String fingerprint = TrustedCertificate.fingerprint(certificate);
		if (fingerprint.equalsIgnoreCase(currentFingerprint)) {
			// déjà accepté : l'échec a une autre cause
			return null;
		}

		System.out.println("Le certificat HTTPS du NAS n'est pas reconnu (certificat auto-signé ?) :");
		System.out.println("  Sujet          : " + certificate.getSubjectX500Principal().getName());
		System.out.println("  Émetteur       : " + certificate.getIssuerX500Principal().getName());
		System.out.println("  Valide jusqu'au: " + certificate.getNotAfter().toInstant().atZone(ZoneId.systemDefault()).toLocalDate());
		System.out.println("  SHA-256        : " + fingerprint);
		System.out.println("Vérifier cette empreinte (navigateur : cadenas > certificat) avant de l'accepter.");
		return ConsoleUtil.promptBool("Faire confiance à ce certificat ?") ? fingerprint : null;
	}

	private static Preferences loadExisting(final Path dir) {
		if (!Preferences.exists(dir)) {
			return null;
		}
		try {
			return Preferences.fromFile(dir);
		} catch (final UserException e) {
			System.out.println("Configuration existante illisible (" + e.getMessage() + ") : elle sera remplacée.");
			return null;
		}
	}

	/**
	 * Complète l'URL saisie : https:// par défaut, sans / final.
	 */
	static String normalizeUrl(final String input) {
		String url = input.trim();
		if (!url.matches("(?i)^https?://.*")) {
			url = "https://" + url;
		}
		while (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		return url;
	}

	private static String promptFolder(final String defaultValue) {
		while (true) {
			final String folder = ConsoleUtil.promptLine("Dossier des photos sur le NAS, vu depuis ce poste (ex : \\\\NAS\\photo) :", defaultValue);
			try {
				if (Files.isDirectory(Paths.get(folder))) {
					return folder;
				}
			} catch (final InvalidPathException e) {
				// message ci-dessous
			}
			System.out.println("Dossier introuvable ou inaccessible : " + folder);
		}
	}

	private static List<Group> promptGroups(final SynoApiClient client, final Preferences existing) {
		final List<SynoGroup> nasGroups = client.getGroups().stream()
				.sorted(Comparator.comparing(SynoGroup::name, String.CASE_INSENSITIVE_ORDER))
				.toList();
		if (nasGroups.isEmpty()) {
			throw new UserException("Aucun groupe local trouvé sur le NAS : en créer un dans DSM puis relancer init.");
		}

		if (existing != null) {
			System.out.println("Groupes actuels : " + ConfigCommands.describeGroups(existing.getGroups()));
			if (ConsoleUtil.promptBool("Conserver ces groupes ?")) {
				ConfigCommands.checkGroups(nasGroups, existing.getGroups());
				return existing.getGroups();
			}
		}

		System.out.println("Groupes du NAS :");
		final List<String> labels = nasGroups.stream()
				.map(g -> g.name() + (existing != null && existing.getGroupById(String.valueOf(g.gid())).isPresent() ? " (actuel)" : ""))
				.toList();
		return ConsoleUtil.promptChoices("Groupes à gérer", labels).stream()
				.map(nasGroups::get)
				.map(g -> new Group(String.valueOf(g.gid()), g.name()))
				.toList();
	}
}
