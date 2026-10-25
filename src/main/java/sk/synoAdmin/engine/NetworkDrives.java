package sk.synoAdmin.engine;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sk.synoAdmin.LogUtil;
import sk.synoAdmin.menu.ProcessRunner;

/**
 * Lecteurs réseau : un dossier reçu sous la forme Z:\... est ramené au chemin UNC du partage,
 * pour être comparé au dossier photos de la configuration (et inversement).
 */
final class NetworkDrives {

	// ligne "    RemotePath    REG_SZ    \\NAS\photo" de reg query
	private static final Pattern REMOTE_PATH = Pattern.compile("RemotePath\\s+REG_\\w+\\s+(\\S.*)");
	private static final Map<Character, Optional<String>> CACHE = new ConcurrentHashMap<>();

	private NetworkDrives() {
		// utility
	}

	/**
	 * @param remoteOf chemin distant d'une lettre de lecteur, vide si ce n'est pas un lecteur réseau
	 * @return le chemin sur le partage si path est sur un lecteur réseau, sinon path inchangé
	 */
	static Path toUnc(final Path path, final Function<Character, Optional<String>> remoteOf) {
		final Path root = path.getRoot();
		if (root == null || root.toString().length() < 2 || root.toString().charAt(1) != ':') {
			return path;
		}
		return remoteOf.apply(root.toString().charAt(0))
				.map(remote -> Paths.get(remote).resolve(root.relativize(path)))
				.orElse(path);
	}

	/** Chemin distant d'un lecteur réseau mémorisé (HKCU\Network\<lettre>), lu une fois par lettre. */
	static Optional<String> mappedRemote(final Character letter) {
		if (!ProcessRunner.isWindows()) {
			return Optional.empty();
		}
		return CACHE.computeIfAbsent(Character.toUpperCase(letter), l -> {
			try {
				final ProcessRunner.Result result = ProcessRunner.run(List.of("reg", "query", "HKCU\\Network\\" + l, "/v", "RemotePath"));
				final Matcher matcher = REMOTE_PATH.matcher(result.output());
				if (result.ok() && matcher.find()) {
					final String remote = matcher.group(1).trim();
					Paths.get(remote); // chemin inutilisable (encodage...) : lecteur ignoré
					return Optional.of(remote);
				}
			} catch (final RuntimeException e) {
				// reg introuvable, chemin invalide : comme un lecteur local
				LogUtil.debug("Network drive " + l + ": " + e);
			}
			return Optional.empty();
		});
	}
}
