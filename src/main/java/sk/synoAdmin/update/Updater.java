package sk.synoAdmin.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

import sk.synoAdmin.UserException;
import sk.synoAdmin.i18n.Messages;

/**
 * Mise à jour vers une nouvelle version : choix du mode (installeur, zip portable ou page de la release), téléchargement
 * avec contrôle de la taille et de l'empreinte SHA-256, puis lancement de l'installeur en mode silencieux.
 */
public final class Updater {

	/**
	 * Mise à jour possible : installation par l'installeur téléchargé, remplacement du dossier portable par le zip, ou page
	 * de la release dans le navigateur.
	 */
	public enum Mode {
		INSTALL, PORTABLE, OPEN_PAGE
	}

	private static final int BUFFER_SIZE = 64 * 1024;
	private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);
	private static final Duration STALL_TIMEOUT = Duration.ofSeconds(60);

	private Updater() {
		// utility
	}

	/**
	 * INSTALL si l'application a été installée par l'installeur (désinstalleur présent) et que la release en fournit un ;
	 * PORTABLE si elle tourne depuis un dossier sans désinstalleur contenant synoRightGui.exe et que la release fournit le
	 * zip ; sinon (jar, fichier manquant) OPEN_PAGE.
	 */
	public static Mode mode(final Release release, final Optional<Path> appDir) {
		if (appDir.isEmpty()) {
			return Mode.OPEN_PAGE;
		}
		if (Files.exists(appDir.get().resolve("unins000.exe"))) {
			return release.setup().isPresent() ? Mode.INSTALL : Mode.OPEN_PAGE;
		}
		return release.zip().isPresent() && Files.exists(appDir.get().resolve("synoRightGui.exe")) ? Mode.PORTABLE
				: Mode.OPEN_PAGE;
	}

	/** Fichier à télécharger pour le mode : installeur (INSTALL), zip (PORTABLE), aucun (OPEN_PAGE). */
	public static Optional<Release.Asset> asset(final Release release, final Mode mode) {
		return switch (mode) {
			case INSTALL -> release.setup();
			case PORTABLE -> release.zip();
			case OPEN_PAGE -> Optional.empty();
		};
	}

	/** Nom du fichier téléchargé pour le mode (nom publié de l'asset) ; IllegalArgumentException pour OPEN_PAGE. */
	public static String fileName(final Release release, final Mode mode) {
		return switch (mode) {
			case INSTALL -> Release.setupName(release.version());
			case PORTABLE -> Release.zipName(release.version());
			case OPEN_PAGE -> throw new IllegalArgumentException("no file to download for " + mode);
		};
	}

	/** Ligne de commande de l'installeur : mise à jour silencieuse, fermeture puis relance de l'application. */
	public static List<String> setupCommand(final Path setup) {
		return List.of(setup.toString(), "/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS", "/RELAUNCH");
	}

	/** Lance l'installeur sans attendre sa fin. */
	public static void launch(final Path setup) throws IOException {
		new ProcessBuilder(setupCommand(setup)).start();
	}

	/**
	 * Télécharge le fichier dans dir sous le nom fileName et le contrôle strictement : sans empreinte publiée, refus avant
	 * tout téléchargement ; fichier supprimé si le téléchargement échoue, est annulé, si la taille reçue n'est pas celle
	 * annoncée ou si l'empreinte SHA-256 ne correspond pas. Abandon si les en-têtes de la réponse n'arrivent pas en 30 s,
	 * ou si plus aucun octet n'arrive pendant 60 s.
	 *
	 * @param length reçoit la taille annoncée par le serveur (-1 si inconnue), avant le premier octet
	 * @param progress reçoit le nombre d'octets lus jusque-là
	 * @param cancelled vrai pour annuler (consulté régulièrement depuis un autre thread)
	 * @throws DownloadCancelledException après une annulation
	 */
	public static Path download(final Release.Asset asset, final String fileName, final Path dir, final LongConsumer length,
			final LongConsumer progress, final BooleanSupplier cancelled) {
		return download(asset, fileName, dir, length, progress, cancelled, RESPONSE_TIMEOUT, STALL_TIMEOUT);
	}

	static Path download(final Release.Asset asset, final String fileName, final Path dir, final LongConsumer length,
			final LongConsumer progress, final BooleanSupplier cancelled, final Duration responseTimeout,
			final Duration stallTimeout) {
		if (asset.sha256().isEmpty()) {
			throw new UserException(Messages.get("update.download.noDigest"));
		}
		final URI uri = URI.create(asset.url());
		final Path target = dir.resolve(fileName);
		final HttpRequest request = HttpRequest.newBuilder(uri).timeout(responseTimeout)
				.header("User-Agent", UpdateChecker.userAgent()).GET().build();
		final MessageDigest digest = sha256();
		final Watchdog watchdog = new Watchdog(Thread.currentThread(), cancelled, stallTimeout);
		Exception error = null;
		long received = 0;
		try {
			final HttpResponse<InputStream> response = UpdateChecker.httpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
			watchdog.activity();
			try (InputStream in = response.body()) {
				if (response.statusCode() != 200) {
					throw new UserException(Messages.get("update.download.failed", response.statusCode()));
				}
				length.accept(response.headers().firstValueAsLong("Content-Length").orElse(-1));
				try (OutputStream out = Files.newOutputStream(target)) {
					final byte[] buffer = new byte[BUFFER_SIZE];
					int read;
					while ((read = in.read(buffer)) != -1) {
						watchdog.activity();
						out.write(buffer, 0, read);
						digest.update(buffer, 0, read);
						received += read;
						progress.accept(received);
					}
				}
			}
		} catch (final IOException | InterruptedException | RuntimeException e) {
			error = e;
		}
		final Watchdog.Reason reason = watchdog.stop();
		if (reason != null || error != null) {
			deleteQuietly(target);
			throw failure(reason, error);
		}
		if (received != asset.size()) {
			deleteQuietly(target);
			throw new UserException(Messages.get("update.download.badSize", received, asset.size()));
		}
		if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(asset.sha256().get())) {
			deleteQuietly(target);
			throw new UserException(Messages.get("update.download.badDigest"));
		}
		return target;
	}

	// annulation ou blocage (interruption provoquée par la surveillance), sinon l'erreur elle-même
	private static RuntimeException failure(final Watchdog.Reason reason, final Exception error) {
		if (reason == Watchdog.Reason.CANCELLED) {
			return new DownloadCancelledException();
		}
		if (reason == Watchdog.Reason.STALLED) {
			return new UserException(Messages.get("update.download.stalled"), error);
		}
		if (error instanceof final RuntimeException runtime) {
			return runtime;
		}
		if (error instanceof InterruptedException) {
			Thread.currentThread().interrupt();
		}
		return new UserException(Messages.get("update.download.error", error.toString()), error);
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (final NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Téléchargement annulé par l'utilisateur (fichier partiel supprimé). */
	public static final class DownloadCancelledException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		DownloadCancelledException() {
			super("download cancelled");
		}
	}

	/**
	 * Surveillance d'un téléchargement depuis un autre thread : interrompt le thread qui télécharge (envoi de la requête
	 * ou lecture bloquée) sur annulation ou faute d'octet reçu pendant le délai.
	 */
	private static final class Watchdog {

		enum Reason {
			CANCELLED, STALLED
		}

		private static final long PERIOD_MS = 100;

		private final Thread worker;
		private final BooleanSupplier cancelled;
		private final long stallNanos;
		private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
			final Thread thread = new Thread(r, "synoRight-download-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		private volatile long lastActivity = System.nanoTime();
		private Reason reason;
		private boolean finished;

		Watchdog(final Thread worker, final BooleanSupplier cancelled, final Duration stallTimeout) {
			this.worker = worker;
			this.cancelled = cancelled;
			this.stallNanos = stallTimeout.toNanos();
			timer.scheduleAtFixedRate(this::check, PERIOD_MS, PERIOD_MS, TimeUnit.MILLISECONDS);
		}

		void activity() {
			lastActivity = System.nanoTime();
		}

		private synchronized void check() {
			if (finished || reason != null) {
				return;
			}
			if (cancelled.getAsBoolean()) {
				reason = Reason.CANCELLED;
			} else if (System.nanoTime() - lastActivity > stallNanos) {
				reason = Reason.STALLED;
			}
			if (reason != null) {
				worker.interrupt();
			}
		}

		/** Fin de la surveillance (sur le thread qui télécharge) : raison de l'interruption, null si aucune. */
		synchronized Reason stop() {
			finished = true;
			timer.shutdownNow();
			if (reason != null) {
				// l'interruption provoquée ici ne doit pas rester sur le thread appelant
				Thread.interrupted();
			}
			return reason;
		}
	}

	private static void deleteQuietly(final Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (final IOException e) {
			// fichier temporaire : tant pis
		}
	}
}
