package sk.synoAdmin.synology;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import sk.synoAdmin.UserException;
import sk.synoAdmin.i18n.Messages;

/**
 * Confiance dans un certificat précis du NAS (typiquement auto-signé), identifié par
 * son empreinte SHA-256 : ce certificat est accepté (sans contrôle du nom d'hôte, il
 * est épinglé), tous les autres suivent la validation standard de Java.
 */
public final class TrustedCertificate {

	private TrustedCertificate() {
		// utility
	}

	/**
	 * Empreinte SHA-256 au format "AB:CD:...", comme l'affichent keytool et les navigateurs.
	 */
	public static String fingerprint(final X509Certificate certificate) {
		try {
			final byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
			return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
		} catch (final GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Contexte TLS acceptant en plus le certificat d'empreinte donnée (null : validation standard seule).
	 */
	static SSLContext sslContext(final String pinnedFingerprint) {
		try {
			if (pinnedFingerprint == null) {
				return SSLContext.getDefault();
			}
			final SSLContext context = SSLContext.getInstance("TLS");
			context.init(null, new TrustManager[] { new PinningTrustManager(pinnedFingerprint, defaultTrustManager()) }, null);
			return context;
		} catch (final GeneralSecurityException e) {
			throw new IllegalStateException(Messages.get("synology.tls.initError"), e);
		}
	}

	/**
	 * Récupère le certificat présenté par le NAS, sans le valider (pour l'afficher à l'utilisateur
	 * avant de lui demander s'il lui fait confiance).
	 */
	public static X509Certificate fetchServerCertificate(final String url) {
		final URI uri = URI.create(url);
		final int port = uri.getPort() == -1 ? 443 : uri.getPort();
		try {
			final SSLContext context = SSLContext.getInstance("TLS");
			final CapturingTrustManager capture = new CapturingTrustManager();
			context.init(null, new TrustManager[] { capture }, null);
			try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(uri.getHost(), port)) {
				socket.setSoTimeout(10_000);
				socket.startHandshake();
			}
			if (capture.serverCertificate == null) {
				throw new UserException(Messages.get("synology.cert.none", url));
			}
			return capture.serverCertificate;
		} catch (final IOException | GeneralSecurityException e) {
			throw new UserException(Messages.get("synology.cert.fetchError", url, e.getMessage()), e);
		}
	}

	private static X509ExtendedTrustManager defaultTrustManager() throws GeneralSecurityException {
		final TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		factory.init((KeyStore) null);
		for (final TrustManager trustManager : factory.getTrustManagers()) {
			if (trustManager instanceof final X509ExtendedTrustManager extended) {
				return extended;
			}
		}
		throw new IllegalStateException(Messages.get("synology.tls.noTrustManager"));
	}

	/**
	 * Accepte le certificat épinglé, délègue tout le reste à la validation standard
	 * (qui contrôle aussi le nom d'hôte : en X509ExtendedTrustManager, c'est à nous de le faire).
	 */
	private static final class PinningTrustManager extends X509ExtendedTrustManager {
		private final String pinnedFingerprint;
		private final X509ExtendedTrustManager delegate;

		PinningTrustManager(final String pinnedFingerprint, final X509ExtendedTrustManager delegate) {
			this.pinnedFingerprint = pinnedFingerprint;
			this.delegate = delegate;
		}

		private boolean isPinned(final X509Certificate[] chain) {
			return chain != null && chain.length > 0 && pinnedFingerprint.equalsIgnoreCase(fingerprint(chain[0]));
		}

		@Override
		public void checkServerTrusted(final X509Certificate[] chain, final String authType, final SSLEngine engine) throws CertificateException {
			if (!isPinned(chain)) {
				delegate.checkServerTrusted(chain, authType, engine);
			}
		}

		@Override
		public void checkServerTrusted(final X509Certificate[] chain, final String authType, final Socket socket) throws CertificateException {
			if (!isPinned(chain)) {
				delegate.checkServerTrusted(chain, authType, socket);
			}
		}

		@Override
		public void checkServerTrusted(final X509Certificate[] chain, final String authType) throws CertificateException {
			if (!isPinned(chain)) {
				delegate.checkServerTrusted(chain, authType);
			}
		}

		@Override
		public void checkClientTrusted(final X509Certificate[] chain, final String authType, final SSLEngine engine) throws CertificateException {
			delegate.checkClientTrusted(chain, authType, engine);
		}

		@Override
		public void checkClientTrusted(final X509Certificate[] chain, final String authType, final Socket socket) throws CertificateException {
			delegate.checkClientTrusted(chain, authType, socket);
		}

		@Override
		public void checkClientTrusted(final X509Certificate[] chain, final String authType) throws CertificateException {
			delegate.checkClientTrusted(chain, authType);
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return delegate.getAcceptedIssuers();
		}
	}

	/**
	 * N'accorde aucune confiance : se contente de mémoriser le certificat présenté
	 * (utilisé uniquement pour l'afficher, aucune donnée n'est échangée).
	 */
	private static final class CapturingTrustManager extends X509ExtendedTrustManager {
		private X509Certificate serverCertificate;

		private void capture(final X509Certificate[] chain) {
			if (chain != null && chain.length > 0) {
				serverCertificate = chain[0];
			}
		}

		@Override
		public void checkServerTrusted(final X509Certificate[] chain, final String authType, final SSLEngine engine) {
			capture(chain);
		}

		@Override
		public void checkServerTrusted(final X509Certificate[] chain, final String authType, final Socket socket) {
			capture(chain);
		}

		@Override
		public void checkServerTrusted(final X509Certificate[] chain, final String authType) {
			capture(chain);
		}

		@Override
		public void checkClientTrusted(final X509Certificate[] chain, final String authType, final SSLEngine engine) throws CertificateException {
			throw new CertificateException("non utilisé");
		}

		@Override
		public void checkClientTrusted(final X509Certificate[] chain, final String authType, final Socket socket) throws CertificateException {
			throw new CertificateException("non utilisé");
		}

		@Override
		public void checkClientTrusted(final X509Certificate[] chain, final String authType) throws CertificateException {
			throw new CertificateException("non utilisé");
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[0];
		}
	}
}
