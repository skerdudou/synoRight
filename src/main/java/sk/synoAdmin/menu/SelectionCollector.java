package sk.synoAdmin.menu;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import sk.synoAdmin.LogUtil;

/**
 * Regroupe une sélection multiple du menu contextuel classique : l'Explorateur y lance un processus
 * par dossier sélectionné. Le premier processus arrivé publie un port local et un jeton dans un fichier
 * du répertoire temporaire de l'utilisateur, attend les dossiers des suivants (jusqu'à un silence de
 * {@link #QUIET}) et traite le tout ; les suivants lui transmettent leurs dossiers et s'arrêtent.
 * Le menu Windows 11 reçoit directement toute la sélection et n'en a pas besoin.
 */
public final class SelectionCollector {

	/** Silence après lequel la sélection est considérée complète (démarrage des autres processus). */
	static final Duration QUIET = Duration.ofMillis(1500);
	/** Durée maximale de collecte. */
	static final Duration MAX = Duration.ofSeconds(20);

	private static final int ATTEMPTS = 5;
	private static final int IO_TIMEOUT_MS = 3000;
	private static final String ACK = "OK";

	private SelectionCollector() {
		// utility
	}

	/**
	 * @param key action (commande, groupe, configuration) : seules les sélections de la même action sont regroupées
	 * @return tous les dossiers de la sélection, ou vide si ceux de ce processus ont été remis à un autre
	 */
	public static Optional<List<String>> collect(final String key, final List<String> folders) {
		return collect(Paths.get(System.getProperty("java.io.tmpdir")), key, folders, QUIET, MAX);
	}

	static Optional<List<String>> collect(final Path dir, final String key, final List<String> folders, final Duration quiet,
			final Duration max) {
		final Path portFile = dir.resolve("synoRight-" + hash(key) + ".collect");
		for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
			try {
				final Optional<Collector> collector = Collector.tryCreate(dir, portFile);
				if (collector.isPresent()) {
					return Optional.of(collector.get().gather(folders, quiet, max));
				}
				final String published = Files.readString(portFile, StandardCharsets.UTF_8);
				if (send(published, folders)) {
					return Optional.empty();
				}
				// collecteur disparu (arrêté ou terminé) : fichier obsolète, sauf s'il a été remplacé entre-temps
				deleteIfContent(portFile, published);
			} catch (final IOException e) {
				LogUtil.debug("Selection collector: " + e);
			}
		}
		// repli : ce processus traite ses dossiers seul
		return Optional.of(folders);
	}

	/** Envoie les dossiers au collecteur publié ("port jeton") ; vrai s'il en a accusé réception. */
	private static boolean send(final String published, final List<String> folders) {
		final String[] parts = published.trim().split(" ");
		if (parts.length != 2) {
			return false;
		}
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), Integer.parseInt(parts[0])), IO_TIMEOUT_MS);
			socket.setSoTimeout(IO_TIMEOUT_MS);
			final Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
			out.write(parts[1] + "\n");
			for (final String folder : folders) {
				out.write(folder + "\n");
			}
			out.flush();
			socket.shutdownOutput();
			final BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			return ACK.equals(in.readLine());
		} catch (final IOException | NumberFormatException e) {
			return false;
		}
	}

	private static void deleteIfContent(final Path file, final String content) {
		try {
			if (Files.readString(file, StandardCharsets.UTF_8).equals(content)) {
				Files.deleteIfExists(file);
			}
		} catch (final IOException e) {
			// déjà supprimé ou remplacé
		}
	}

	static String hash(final String key) {
		try {
			final byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 8);
		} catch (final NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Processus collecteur : port local ouvert et publié. */
	private static final class Collector {
		private final ServerSocket server;
		private final Path portFile;
		private final String published;
		private final String token;

		private Collector(final ServerSocket server, final Path portFile, final String published, final String token) {
			this.server = server;
			this.portFile = portFile;
			this.published = published;
			this.token = token;
		}

		/** Publie le port de façon atomique (renommage) ; vide si un autre collecteur est déjà publié. */
		static Optional<Collector> tryCreate(final Path dir, final Path portFile) throws IOException {
			final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
			final byte[] random = new byte[16];
			new SecureRandom().nextBytes(random);
			final String token = HexFormat.of().formatHex(random);
			final String published = server.getLocalPort() + " " + token;
			final Path tmp = Files.createTempFile(dir, "synoRight-", ".tmp");
			try {
				Files.writeString(tmp, published, StandardCharsets.UTF_8);
				Files.move(tmp, portFile); // échoue si le fichier existe
				return Optional.of(new Collector(server, portFile, published, token));
			} catch (final FileAlreadyExistsException e) {
				server.close();
				return Optional.empty();
			} catch (final IOException e) {
				server.close();
				throw e;
			} finally {
				Files.deleteIfExists(tmp);
			}
		}

		List<String> gather(final List<String> own, final Duration quiet, final Duration max) {
			final List<String> folders = new ArrayList<>(own);
			final long deadline = System.nanoTime() + max.toNanos();
			try (server) {
				server.setSoTimeout((int) quiet.toMillis());
				while (System.nanoTime() < deadline && acceptOne(folders)) {
					// silence relancé à chaque envoi reçu
				}
				// plus de nouveaux arrivants : ceux déjà connectés sont servis, les suivants réessaieront
				deleteIfContent(portFile, published);
				server.setSoTimeout(100);
				while (acceptOne(folders)) {
					// vidage de la file d'attente
				}
			} catch (final IOException e) {
				// port local en erreur : les dossiers déjà reçus (et acquittés) sont traités quand même
				LogUtil.debug("Selection collector: " + e);
			} finally {
				deleteIfContent(portFile, published);
			}
			return folders.stream().distinct().toList();
		}

		// faux si aucun envoi n'est arrivé dans le délai
		private boolean acceptOne(final List<String> folders) throws IOException {
			final Socket socket;
			try {
				socket = server.accept();
			} catch (final SocketTimeoutException e) {
				return false;
			}
			try (socket) {
				socket.setSoTimeout(IO_TIMEOUT_MS);
				final BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
				// jeton : seul un processus ayant pu lire le fichier (même utilisateur) est écouté
				if (!token.equals(in.readLine())) {
					return true;
				}
				final List<String> received = new ArrayList<>();
				for (String line = in.readLine(); line != null; line = in.readLine()) {
					if (!line.isBlank()) {
						received.add(line);
					}
				}
				final Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
				out.write(ACK + "\n");
				out.flush();
				folders.addAll(received);
			} catch (final IOException e) {
				// envoi interrompu : l'émetteur, sans accusé de réception, réessaiera
				LogUtil.debug("Selection collector: " + e);
			}
			return true;
		}
	}
}
