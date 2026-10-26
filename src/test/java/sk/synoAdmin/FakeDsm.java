package sk.synoAdmin;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;
import javax.net.ssl.SSLContext;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * Faux DSM pour les tests : login toujours accepté, albums Synology Photos et
 * leurs groupes autorisés, mises à jour de droits enregistrées.
 */
public final class FakeDsm implements AutoCloseable {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int ROOT = 0;

	private record Album(int id, String name, List<Integer> groupIds) {
	}

	private record Photo(int id, String fileName, boolean thumbReady) {
	}

	private final Map<Integer, List<Album>> children = new HashMap<>();
	private final Map<Integer, List<Photo>> photos = new HashMap<>();
	private final List<String> permissionUpdates = new CopyOnWriteArrayList<>();
	private final AtomicInteger failingListings = new AtomicInteger();
	private final HttpServer server;

	public FakeDsm() throws IOException {
		this(null);
	}

	/**
	 * @param tls contexte TLS du serveur (certificat présenté), null pour du HTTP simple
	 */
	public FakeDsm(final SSLContext tls) throws IOException {
		if (tls == null) {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		} else {
			final HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			httpsServer.setHttpsConfigurator(new HttpsConfigurator(tls));
			server = httpsServer;
		}
		server.createContext("/", this::handle);
		server.start();
	}

	public String url() {
		return (server instanceof HttpsServer ? "https" : "http") + "://127.0.0.1:" + server.getAddress().getPort();
	}

	/**
	 * Déclare un album (nom complet, ex : "/2024/vacances") sous un parent (null : racine).
	 */
	public FakeDsm album(final Integer parentId, final int id, final String name, final Integer... groupIds) {
		children.computeIfAbsent(parentId == null ? ROOT : parentId, k -> new ArrayList<>())
				.add(new Album(id, name, List.of(groupIds)));
		return this;
	}

	/**
	 * Déclare un élément dans un dossier ; sa vignette est prête ou en erreur ("broken").
	 */
	public FakeDsm photo(final int folderId, final int id, final String fileName, final boolean thumbReady) {
		photos.computeIfAbsent(folderId, k -> new ArrayList<>()).add(new Photo(id, fileName, thumbReady));
		return this;
	}

	/**
	 * Fait échouer les count prochaines listes de dossiers (erreur passagère du NAS).
	 */
	public FakeDsm failListings(final int count) {
		failingListings.set(count);
		return this;
	}

	/**
	 * Paramètres "permission" reçus par SYNO.FotoTeam.Sharing.FolderBatchPermission.
	 */
	public List<String> permissionUpdates() {
		return permissionUpdates;
	}

	@Override
	public void close() {
		server.stop(0);
	}

	private void handle(final HttpExchange exchange) throws IOException {
		final Map<String, String> params = parseForm(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

		final ObjectNode response = MAPPER.createObjectNode().put("success", true);
		switch (params.getOrDefault("api", "")) {
			case "SYNO.API.Auth" -> response.putObject("data").put("sid", "sid").put("synotoken", "tok");
			case "SYNO.Core.Desktop.Timeout" -> {
				// session toujours valide une fois loggé
			}
			case "SYNO.FotoTeam.Browse.Folder" -> {
				if (failingListings.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
					response.put("success", false).putObject("error").put("code", 117); // erreur passagère simulée
					break;
				}
				final int parent = params.containsKey("id") ? Integer.parseInt(params.get("id")) : ROOT;
				final ArrayNode list = response.putObject("data").putArray("list");
				for (final Album album : children.getOrDefault(parent, List.of())) {
					final ArrayNode permissions = list.addObject().put("id", album.id()).put("name", album.name())
							.putObject("additional").putObject("sharing_info").putArray("permission");
					album.groupIds().forEach(gid -> permissions.addObject().put("id", gid).put("type", "group"));
				}
			}
			case "SYNO.FotoTeam.Browse.Item" -> {
				if (failingListings.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
					response.put("success", false).putObject("error").put("code", 117);
					break;
				}
				final int limit = Integer.parseInt(params.getOrDefault("limit", "100"));
				final ArrayNode list = response.putObject("data").putArray("list");
				photos.getOrDefault(Integer.parseInt(params.get("folder_id")), List.of()).stream().limit(limit).forEach(photo -> {
					final ObjectNode item = list.addObject().put("id", photo.id()).put("filename", photo.fileName()).put("type", "photo");
					final String state = photo.thumbReady() ? "ready" : "broken";
					item.putObject("additional").putObject("thumbnail").put("unit_id", photo.id()).put("cache_key", photo.id() + "_1")
							.put("sm", state).put("xl", state);
				});
			}
			case "SYNO.FotoTeam.Thumbnail" -> {
				final boolean ready = photos.values().stream().flatMap(List::stream)
						.anyMatch(photo -> String.valueOf(photo.id()).equals(params.get("id")) && photo.thumbReady());
				if (ready) {
					final byte[] jpeg = tinyJpeg();
					exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
					exchange.sendResponseHeaders(200, jpeg.length);
					exchange.getResponseBody().write(jpeg);
					exchange.close();
					return;
				}
				response.put("success", false).putObject("error").put("code", 120);
			}
			case "SYNO.FotoTeam.Sharing.FolderBatchPermission" -> permissionUpdates.add(params.get("permission"));
			default -> response.put("success", false).putObject("error").put("code", 102);
		}

		final byte[] bytes = MAPPER.writeValueAsBytes(response);
		exchange.sendResponseHeaders(200, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	private static byte[] tinyJpeg() throws IOException {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "jpg", out);
		return out.toByteArray();
	}

	private static Map<String, String> parseForm(final String body) {
		final Map<String, String> params = new HashMap<>();
		for (final String pair : body.split("&")) {
			final String[] kv = pair.split("=", 2);
			if (kv.length == 2) {
				params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
			}
		}
		return params;
	}
}
