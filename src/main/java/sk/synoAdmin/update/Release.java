package sk.synoAdmin.update;

import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Version publiée sur GitHub : numéro (sans « v » initial), page de la release, et installeur avec son empreinte SHA-256 s'ils
 * sont présents.
 */
public record Release(String version, String pageUrl, Optional<String> setupUrl, Optional<String> setupSha256) {

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
		final String setupName = "synoRight-" + version + "-setup.exe";
		Optional<String> setupUrl = Optional.empty();
		Optional<String> setupSha256 = Optional.empty();
		for (final JsonNode asset : root.path("assets")) {
			if (!setupName.equals(asset.path("name").asString(""))) {
				continue;
			}
			final String url = asset.path("browser_download_url").asString("");
			if (url.isBlank()) {
				break;
			}
			setupUrl = Optional.of(url);
			final String digest = asset.path("digest").asString("");
			if (digest.startsWith(DIGEST_PREFIX) && digest.length() > DIGEST_PREFIX.length()) {
				setupSha256 = Optional.of(digest.substring(DIGEST_PREFIX.length()));
			}
			break;
		}
		return new Release(version, root.path("html_url").asString(""), setupUrl, setupSha256);
	}
}
