package sk.synoAdmin.synology;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.net.ssl.SSLException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import sk.synoAdmin.ConsoleUtil;
import sk.synoAdmin.LogUtil;
import sk.synoAdmin.UserException;
import sk.synoAdmin.engine.Folder;
import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.prefs.Session;

public class SynoApiClient {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final int MAX_ATTEMPTS = 3;
	// codes d'erreur de SYNO.API.Auth
	private static final int ERR_WRONG_PASSWORD = 400;
	private static final int ERR_OTP_REQUIRED = 403;
	private static final int ERR_OTP_WRONG = 404;

	private final String url;
	private final String user;
	private final String initialPassword;
	private final boolean passwordFromConfig;
	private final Session session;
	private final Consumer<String> onPasswordCorrected;
	private final HttpClient httpClient;
	private String cookieHeader;
	private String synoToken;

	/**
	 * Client sur la configuration : si le mot de passe mémorisé est refusé, celui saisi
	 * en remplacement peut être mémorisé à la place.
	 */
	public SynoApiClient(final Preferences pref) {
		this(pref.getUrl(), pref.getUser(), pref.getPassword(), true, pref.getSession(), pref.getCertFingerprint(), newPassword -> {
			if (ConsoleUtil.promptBool(Messages.get("synology.password.remember"))) {
				pref.setPassword(newPassword);
				pref.save();
				System.out.println(Messages.get("synology.password.updated"));
			}
		});
	}

	/**
	 * @param password mot de passe essayé en premier (null : demandé en console)
	 * @param passwordFromConfig true si ce mot de passe vient de la configuration (pour les messages)
	 * @param onPasswordCorrected appelé avec le mot de passe ressaisi quand le premier a été refusé puis corrigé (peut être null)
	 */
	public SynoApiClient(final String url, final String user, final String password, final boolean passwordFromConfig, final Session session, final Consumer<String> onPasswordCorrected) {
		this(url, user, password, passwordFromConfig, session, null, onPasswordCorrected);
	}

	/**
	 * @param certFingerprint empreinte SHA-256 d'un certificat auto-signé à accepter (null : validation standard seule)
	 */
	public SynoApiClient(final String url, final String user, final String password, final boolean passwordFromConfig, final Session session, final String certFingerprint, final Consumer<String> onPasswordCorrected) {
		this.url = url;
		this.user = user;
		initialPassword = password;
		this.passwordFromConfig = passwordFromConfig;
		this.session = session;
		this.onPasswordCorrected = onPasswordCorrected;
		httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				// suit les redirections (comportement du client Jersey d'origine)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.sslContext(TrustedCertificate.sslContext(certFingerprint))
				.build();
	}

	public CompletableFuture<Void> loginAsynch() {
		return CompletableFuture.runAsync(this::login);
	}

	private void initCookies() {
		final StringBuilder cookies = new StringBuilder();
		if (session.getCookieSession() != null) {
			cookies.append("id=").append(session.getCookieSession());
		}
		if (session.getCookieDid() != null) {
			if (cookies.length() > 0) {
				cookies.append("; ");
			}
			cookies.append("did=").append(session.getCookieDid());
		}
		cookieHeader = cookies.length() > 0 ? cookies.toString() : null;
		synoToken = session.getSynoToken();
	}

	public void login() {
		LogUtil.runMonitored(this::doLogin, "Login time");
	}

	private void doLogin() {
		initCookies();

		// session mémorisée encore valide ?
		if (session.getCookieSession() != null) {
			final Map<String, String> params = new LinkedHashMap<>();
			params.put("api", "SYNO.Core.Desktop.Timeout");
			params.put("method", "check");
			params.put("version", "1");

			if (isSuccess(doRequest("/webapi/entry.cgi", params))) {
				return;
			}
			LogUtil.debug("Session expirée, nouveau login");
		}

		String pwd = initialPassword != null ? initialPassword : ConsoleUtil.promptSecret(Messages.get("synology.login.password", user));

		for (int attempt = 1;; attempt++) {
			final Map<String, String> params = new LinkedHashMap<>();
			params.put("api", "SYNO.API.Auth");
			params.put("method", "login");
			params.put("enable_syno_token", "yes");
			params.put("rememberme", "1");
			params.put("version", "7");
			params.put("account", user);
			params.put("passwd", pwd);

			JsonNode response = doRequest("/webapi/entry.cgi?api=SYNO.API.Auth", params);

			// double authentification : code OTP, redemandé s'il est faux
			for (int otpAttempt = 1; !isSuccess(response) && isOtpError(response) && otpAttempt <= MAX_ATTEMPTS; otpAttempt++) {
				if (errorCode(response) == ERR_OTP_WRONG) {
					System.out.println(Messages.get("synology.login.otpWrong"));
				}
				params.put("otp_code", ConsoleUtil.promptLine(Messages.get("synology.login.otpPrompt")));
				params.put("enable_device_token", "yes");
				response = doRequest("/webapi/entry.cgi?api=SYNO.API.Auth", params);
			}

			if (isSuccess(response)) {
				final JsonNode data = response.path("data");
				session.update(data.path("sid").asString(null), data.path("synotoken").asString(null), data.path("device_id").asString(null));
				initCookies();

				if (initialPassword != null && !initialPassword.equals(pwd) && onPasswordCorrected != null) {
					onPasswordCorrected.accept(pwd);
				}
				return;
			}

			final int code = errorCode(response);
			if (code == ERR_WRONG_PASSWORD && attempt < MAX_ATTEMPTS) {
				System.out.println(passwordFromConfig && initialPassword != null && initialPassword.equals(pwd)
						? Messages.get("synology.login.storedRefused")
						: Messages.get("synology.login.badCredentials"));
				pwd = ConsoleUtil.promptSecret(Messages.get("synology.login.password", user));
				continue;
			}

			throw new UserException(Messages.get("synology.login.failed", url, user, describeLoginError(code)));
		}
	}

	private static boolean isOtpError(final JsonNode response) {
		final int code = errorCode(response);
		return code == ERR_OTP_REQUIRED || code == ERR_OTP_WRONG || "otp".equals(loginErrorType(response));
	}

	/**
	 * Traduit les codes d'erreur de SYNO.API.Auth.
	 */
	static String describeLoginError(final int code) {
		return switch (code) {
			case ERR_WRONG_PASSWORD -> Messages.get("synology.loginError.wrongPassword");
			case 401 -> Messages.get("synology.loginError.disabled");
			case 402 -> Messages.get("synology.loginError.denied");
			case ERR_OTP_REQUIRED -> Messages.get("synology.loginError.otpRequired");
			case ERR_OTP_WRONG -> Messages.get("synology.loginError.otpWrong");
			case 406 -> Messages.get("synology.loginError.otpNotEnabled");
			case 407 -> Messages.get("synology.loginError.ipBlocked");
			case 408, 409, 410 -> Messages.get("synology.loginError.passwordExpired");
			case -1 -> Messages.get("synology.loginError.unexpected");
			default -> Messages.get("synology.loginError.other", code);
		};
	}

	private static boolean isSuccess(final JsonNode response) {
		return response.path("success").asBoolean();
	}

	private static int errorCode(final JsonNode response) {
		return response.path("error").path("code").asInt(-1);
	}

	// complément de message d'erreur d'API : " (code xxx)"
	private static String errorSuffix(final JsonNode response) {
		final int code = errorCode(response);
		return code == -1 ? "" : " " + Messages.get("synology.error.suffix", code);
	}

	// type de la première erreur d'une réponse de login (ex : "otp")
	private static String loginErrorType(final JsonNode response) {
		return response.path("error").path("errors").path("types").path(0).path("type").asString(null);
	}

	/**
	 * Liste les groupes locaux du NAS (API SYNO.Core.Group).
	 */
	public List<SynoGroup> getGroups() {
		final Map<String, String> params = new LinkedHashMap<>();
		params.put("api", "SYNO.Core.Group");
		params.put("method", "list");
		params.put("version", "1");
		params.put("offset", "0");
		params.put("limit", "5000");
		params.put("type", "local");
		// sans name_only=false, DSM ne renvoie que les noms (pas de gid)
		params.put("name_only", "false");

		final JsonNode response = doRequest("/webapi/entry.cgi", params);

		if (!isSuccess(response)) {
			throw new UserException(Messages.get("synology.groups.listError", errorSuffix(response)));
		}

		return parseGroupList(response);
	}

	static List<SynoGroup> parseGroupList(final JsonNode response) {
		final List<SynoGroup> groups = new ArrayList<>();
		for (final JsonNode group : response.path("data").path("groups")) {
			final String name = group.path("name").asString();
			if (!group.path("gid").isNumber()) {
				throw new UserException(Messages.get("synology.groups.noGid", name));
			}
			groups.add(new SynoGroup(group.path("gid").asInt(), name));
		}
		return groups;
	}

	/**
	 * Liste les droits des dossiers racine et de leurs enfants directs.
	 */
	public List<SynoFolderPermission> getAccessRight() {
		final List<SynoFolderPermission> list = new ArrayList<>();

		final List<SynoFolderPermission> rootFolders = listFolderPermissions(null);
		list.addAll(rootFolders);

		for (final SynoFolderPermission rootFolder : rootFolders) {
			list.addAll(listFolderPermissions(rootFolder.id()));
		}

		return list;
	}

	/**
	 * Liste les dossiers Synology Photos directement sous le dossier donné (racine si null),
	 * avec les groupes autorisés.
	 */
	public List<SynoFolderPermission> listFolderPermissions(final Integer id) {
		LogUtil.debug("List folders for id " + id);

		final Map<String, String> params = new LinkedHashMap<>();
		params.put("api", "SYNO.FotoTeam.Browse.Folder");
		params.put("method", "list");
		params.put("version", "2");
		if (id != null) {
			params.put("id", id.toString());
		}
		params.put("offset", "0");
		params.put("limit", "5000");
		params.put("additional", "[\"sharing_info\"]");

		final JsonNode response = doRequest("/webapi/entry.cgi/SYNO.FotoTeam.Browse.Folder", params);

		if (!isSuccess(response)) {
			throw new UserException(Messages.get("synology.rights.readError", errorSuffix(response)));
		}

		return parseFolderList(response);
	}

	static List<SynoFolderPermission> parseFolderList(final JsonNode response) {
		final List<SynoFolderPermission> folders = new ArrayList<>();
		for (final JsonNode folder : response.path("data").path("list")) {
			final List<Integer> browseGrpIds = new ArrayList<>();
			for (final JsonNode permission : folder.path("additional").path("sharing_info").path("permission")) {
				browseGrpIds.add(permission.path("id").asInt());
			}
			folders.add(new SynoFolderPermission(folder.path("id").asInt(), folder.path("name").asString(), browseGrpIds));
		}
		return folders;
	}

	public void setAccessRight(final String gid, final List<Folder> foldersToAddList, final List<Folder> foldersToDeleteList) {
		final Map<String, String> params = new LinkedHashMap<>();
		params.put("api", "SYNO.FotoTeam.Sharing.FolderBatchPermission");
		params.put("version", "1");
		params.put("method", "update_by_member");
		params.put("permission", buildPermissionParam(gid, foldersToAddList, foldersToDeleteList));

		final JsonNode response = doRequest("/webapi/entry.cgi/SYNO.FotoTeam.Sharing.FolderBatchPermission", params);

		if (!isSuccess(response)) {
			throw new UserException(Messages.get("synology.rights.writeError", errorSuffix(response)));
		}
	}

	// construit le JSON de mise à jour des droits d'un groupe : ajouts avec le rôle download, suppressions
	static String buildPermissionParam(final String gid, final List<Folder> foldersToAddList, final List<Folder> foldersToDeleteList) {
		final ArrayNode actions = MAPPER.createArrayNode();
		for (final Folder folder : foldersToAddList) {
			actions.addObject()
					.put("folder_id", folder.getId())
					.put("action", "update")
					.put("role", "download");
		}
		for (final Folder folder : foldersToDeleteList) {
			actions.addObject()
					.put("folder_id", folder.getId())
					.put("action", "delete");
		}

		final ObjectNode permission = MAPPER.createObjectNode();
		permission.put("id", Long.parseLong(gid));
		permission.put("type", "group");
		permission.set("list", actions);

		return permission.toString();
	}

	public void addAccessRight(final String gid, final Folder... foldersToAdd) {
		setAccessRight(gid, Arrays.asList(foldersToAdd), List.of());
	}

	public void removeAccessRight(final String gid, final Folder... folderToDelete) {
		setAccessRight(gid, List.of(), Arrays.asList(folderToDelete));
	}

	private JsonNode doRequest(final String path, final Map<String, String> params) {
		final String body = params.entrySet().stream()
				.map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
				.collect(Collectors.joining("&"));

		final HttpRequest.Builder builder;
		try {
			// URI.create accepte des URL que HttpRequest refuse ensuite (ex : hôte accentué)
			builder = HttpRequest.newBuilder(URI.create(url + path));
		} catch (final IllegalArgumentException e) {
			throw new UserException(Messages.get("synology.url.invalid", url));
		}

		builder.timeout(Duration.ofSeconds(60))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body));

		if (cookieHeader != null) {
			builder.header("Cookie", cookieHeader);
		}
		if (synoToken != null) {
			builder.header("X-SYNO-TOKEN", synoToken);
		}

		try {
			final HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
			return MAPPER.readTree(response.body());
		} catch (final IOException | JacksonException e) {
			// JacksonException (non contrôlée depuis Jackson 3) : réponse qui n'est pas du JSON
			throw new UserException(Messages.get("synology.comm.error", url, describeIoError(e)), e);
		} catch (final IllegalArgumentException e) {
			throw new UserException(Messages.get("synology.comm.error", url, describeIoError(e)), e);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
	}

	/**
	 * Explique les erreurs réseau courantes (NAS injoignable, certificat, mauvaise URL).
	 */
	static String describeIoError(final Exception e) {
		Throwable cause = e;
		while (cause != null) {
			if (cause instanceof UnresolvedAddressException) {
				return Messages.get("synology.io.unknownHost");
			}
			if (cause instanceof SSLException) {
				return Messages.get("synology.io.ssl", cause.getMessage());
			}
			if (cause instanceof HttpTimeoutException) {
				return Messages.get("synology.io.timeout");
			}
			if (cause instanceof ConnectException) {
				return Messages.get("synology.io.unreachable");
			}
			if (cause instanceof JacksonException) {
				return Messages.get("synology.io.notJson");
			}
			cause = cause.getCause();
		}
		return e.getMessage();
	}

	private static String urlEncode(final String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
