package sk.synoAdmin.update;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sk.synoAdmin.AppVersion;
import sk.synoAdmin.UserException;
import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.menu.ProcessRunner;

/**
 * Recherche d'une nouvelle version sur GitHub (dernière release du dépôt), et mémorisation de la date de la dernière
 * vérification dans le registre.
 */
public final class UpdateChecker {

	static final URI LATEST_URL = URI.create("https://api.github.com/repos/skerdudou/synoRight/releases/latest");

	private static final String UPDATE_KEY = "HKCU\\Software\\synoRight\\Update";
	private static final String LAST_CHECK = "LastCheck";
	private static final Duration CHECK_INTERVAL = Duration.ofHours(24);
	private static final Pattern LAST_CHECK_LINE = Pattern.compile("^\\s+" + LAST_CHECK + "\\s+REG_SZ\\s+(\\d+)\\s*$");

	private UpdateChecker() {
		// utility
	}

	/** Vrai si la release est strictement plus récente que la version courante. */
	public static boolean newer(final Release release, final String current) {
		return AppVersion.compare(release.version(), current) > 0;
	}

	/** Dernière release publiée sur GitHub. */
	public static Release fetchLatest() {
		return fetchLatest(LATEST_URL);
	}

	static Release fetchLatest(final URI uri) {
		final HttpClient client = httpClient();
		final HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
				.header("Accept", "application/vnd.github+json")
				.header("User-Agent", userAgent()).GET().build();
		try {
			final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw new UserException(Messages.get("update.check.failed", response.statusCode()));
			}
			return parseResponse(response.body());
		} catch (final IOException e) {
			throw new UserException(Messages.get("update.check.unreachable", e.toString()), e);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new UserException(Messages.get("update.check.unreachable", e.toString()), e);
		}
	}

	/** Client HTTP commun (vérification et téléchargement) : proxy du système, redirections suivies, délai de connexion de 10 s. */
	static HttpClient httpClient() {
		// proxy du système (réglages Windows), sauf si l'utilisateur en a déjà décidé autrement
		if (System.getProperty("java.net.useSystemProxies") == null) {
			System.setProperty("java.net.useSystemProxies", "true");
		}
		return HttpClient.newBuilder().proxy(ProxySelector.getDefault())
				.followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
	}

	/** En-tête User-Agent des requêtes (exigé par l'API GitHub). */
	static String userAgent() {
		return "synoRight/" + AppVersion.current().orElse("dev");
	}

	// corps non JSON (page de portail captif, proxy), tronqué ou sans version : réponse inattendue
	private static Release parseResponse(final String body) {
		try {
			return Release.parse(body);
		} catch (final RuntimeException e) {
			throw new UserException(Messages.get("update.check.invalidResponse"), e);
		}
	}

	/** Vrai si la dernière vérification est absente, date de 24 h ou plus, ou est dans le futur (horloge déréglée). */
	public static boolean dueForCheck(final Optional<Instant> last, final Instant now) {
		return last.isEmpty() || last.get().isAfter(now) || !last.get().plus(CHECK_INTERVAL).isAfter(now);
	}

	/**
	 * Lance la vérification puis enregistre la date, que la vérification ait réussi ou non ; l'échec est relancé tel quel.
	 */
	public static Release checkAndRecord(final Supplier<Release> fetch, final Consumer<Instant> record,
			final Supplier<Instant> clock) {
		try {
			return fetch.get();
		} finally {
			record.accept(clock.get());
		}
	}

	/** Vérification sur GitHub avec enregistrement de la date dans le registre (réussie ou non). */
	public static Release fetchLatestAndRecord() {
		return checkAndRecord(UpdateChecker::fetchLatest, UpdateChecker::markChecked, Instant::now);
	}

	/** Date de la dernière vérification (registre) ; vide si absente ou hors Windows. */
	public static Optional<Instant> lastCheck() {
		if (!ProcessRunner.isWindows()) {
			return Optional.empty();
		}
		final ProcessRunner.Result result = ProcessRunner.run(List.of("reg", "query", UPDATE_KEY, "/v", LAST_CHECK));
		return result.ok() ? parseLastCheck(result.output()) : Optional.empty();
	}

	/** Analyse la sortie de « reg query KEY /v LastCheck » (epoch en secondes). */
	static Optional<Instant> parseLastCheck(final String regQueryOutput) {
		if (regQueryOutput == null) {
			return Optional.empty();
		}
		for (final String line : regQueryOutput.split("\\r?\\n")) {
			final Matcher matcher = LAST_CHECK_LINE.matcher(line);
			if (matcher.matches()) {
				try {
					return Optional.of(Instant.ofEpochSecond(Long.parseLong(matcher.group(1))));
				} catch (final NumberFormatException | java.time.DateTimeException e) {
					return Optional.empty();
				}
			}
		}
		return Optional.empty();
	}

	/** Mémorise la date de la dernière vérification (registre) ; sans effet hors Windows. */
	public static void markChecked(final Instant when) {
		if (!ProcessRunner.isWindows()) {
			return;
		}
		ProcessRunner.run(List.of("reg", "add", UPDATE_KEY, "/v", LAST_CHECK, "/t", "REG_SZ", "/d",
				Long.toString(when.getEpochSecond()), "/f"));
	}
}
