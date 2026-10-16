package sk.synoAdmin.synology;

import java.security.cert.X509Certificate;
import java.time.ZoneId;

import javax.net.ssl.SSLHandshakeException;

import sk.synoAdmin.ConsoleUtil;
import sk.synoAdmin.UserException;
import sk.synoAdmin.prefs.Session;

/**
 * Connexion au NAS partagée par l'assistant {@code init} et l'IHM : login sur une session de travail
 * et, pour un certificat auto-signé, proposition de lui faire confiance.
 */
public final class ConnectionCheck {

	/**
	 * Résultat d'une connexion réussie.
	 *
	 * @param client client connecté
	 * @param workSession session de travail (la session mémorisée n'est pas modifiée)
	 * @param password mot de passe effectivement utilisé (corrigé pendant le login le cas échéant)
	 * @param fingerprint empreinte du certificat de confiance, null si aucune
	 */
	public record Result(SynoApiClient client, Session workSession, String password, String fingerprint) {
	}

	private ConnectionCheck() {
		// utilitaire
	}

	/**
	 * Se connecte au NAS sur une copie de la session ; un certificat non reconnu est proposé à l'acceptation
	 * puis la connexion est retentée avec les mêmes identifiants.
	 *
	 * @param passwordFromConfig true si le mot de passe vient de la configuration (pas de nouvelle saisie proposée)
	 * @param sameAccount true si c'est le même compte que la configuration : le device id est conservé (pas d'OTP)
	 * @throws UserException si la connexion échoue
	 */
	public static Result connect(final String url, final String user, final String password, final boolean passwordFromConfig,
			final Session session, final boolean sameAccount, final String fingerprint) {
		final String[] currentPassword = { password };
		String currentFingerprint = fingerprint;
		// session de travail : la session mémorisée n'est remplacée qu'à l'enregistrement ; même compte : device id conservé (pas d'OTP)
		final Session workSession = session.transientCopy(sameAccount);
		while (true) {
			final SynoApiClient client = new SynoApiClient(url, user, currentPassword[0], passwordFromConfig, workSession,
					currentFingerprint, newPassword -> currentPassword[0] = newPassword);
			try {
				client.login();
				return new Result(client, workSession, currentPassword[0], currentFingerprint);
			} catch (final UserException e) {
				// certificat auto-signé : proposer de lui faire confiance, puis réessayer avec les mêmes identifiants
				final String trusted = isCertificateError(e) ? promptTrustCertificate(url, currentFingerprint) : null;
				if (trusted == null) {
					throw e;
				}
				currentFingerprint = trusted;
			}
		}
	}

	/**
	 * Indique si l'erreur vient d'un certificat HTTPS non reconnu.
	 */
	public static boolean isCertificateError(final Throwable e) {
		for (Throwable cause = e; cause != null; cause = cause.getCause()) {
			if (cause instanceof SSLHandshakeException) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Complète l'URL saisie : https:// par défaut, sans / final.
	 */
	public static String normalizeUrl(final String input) {
		String url = input.trim();
		if (!url.matches("(?i)^https?://.*")) {
			url = "https://" + url;
		}
		while (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		return url;
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
			ConsoleUtil.info(e.getMessage());
			return null;
		}
		final String fingerprint = TrustedCertificate.fingerprint(certificate);
		if (fingerprint.equalsIgnoreCase(currentFingerprint)) {
			// déjà accepté : l'échec a une autre cause
			return null;
		}

		final String text = "Le certificat HTTPS du NAS n'est pas reconnu (certificat auto-signé ?) :\n"
				+ "  Sujet          : " + certificate.getSubjectX500Principal().getName() + "\n"
				+ "  Émetteur       : " + certificate.getIssuerX500Principal().getName() + "\n"
				+ "  Valide jusqu'au: " + certificate.getNotAfter().toInstant().atZone(ZoneId.systemDefault()).toLocalDate() + "\n"
				+ "  SHA-256        : " + fingerprint + "\n"
				+ "Vérifier cette empreinte (navigateur : cadenas > certificat) avant de l'accepter.";
		return ConsoleUtil.promptBool(text + "\nFaire confiance à ce certificat ?") ? fingerprint : null;
	}
}
