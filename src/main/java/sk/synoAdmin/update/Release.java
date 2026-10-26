package sk.synoAdmin.update;

import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Version publiée sur GitHub : numéro (sans « v » initial), page de la release, et fichiers de mise à jour (installeur,
 * zip portable) s'ils sont publiés avec leur taille et leur empreinte SHA-256.
 */
public record Release(String version, String pageUrl, Optional<Asset> setup, Optional<Asset> zip) {

	/** Fichier publié : adresse de téléchargement, taille attendue en octets et empreinte SHA-256 si GitHub en fournit une. */
	public record Asset(String url, long size, Optional<String> sha256) {
	}

	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final String DIGEST_PREFIX = "sha256:";

	/** Analyse la réponse JSON de l'API « releases/latest ». */
	public static Release parse(final String json) {
		final JsonNode root = MAPPER.readTree(json);
		String version = root.path("tag_name").asString("");
		if (version.startsWith("v") || version.startsWith("V")) {
			version = version.substring(1);
		}
		if (version.isBlank()) {
			throw new IllegalArgumentException("tag_name absent");
		}
		return new Release(version, root.path("html_url").asString(""), asset(root, setupName(version)),
				asset(root, zipName(version)));
	}

	/** Nom publié de l'installeur de la version. */
	static String setupName(final String version) {
		return "synoRight-" + version + "-setup.exe";
	}

	/** Nom publié du zip portable de la version. */
	static String zipName(final String version) {
		return "synoRight-" + version + "-windows-x64.zip";
	}

	// sans adresse ni taille, le fichier ne peut pas être contrôlé : il est ignoré
	private static Optional<Asset> asset(final JsonNode root, final String name) {
		for (final JsonNode node : root.path("assets")) {
			if (!name.equals(node.path("name").asString(""))) {
				continue;
			}
			final String url = node.path("browser_download_url").asString("");
			final long size = node.path("size").asLong(0);
			if (url.isBlank() || size <= 0) {
				return Optional.empty();
			}
			final String digest = node.path("digest").asString("");
			final Optional<String> sha256 = digest.startsWith(DIGEST_PREFIX) && digest.length() > DIGEST_PREFIX.length()
					? Optional.of(digest.substring(DIGEST_PREFIX.length()))
					: Optional.empty();
			return Optional.of(new Asset(url, size, sha256));
		}
		return Optional.empty();
	}
}
