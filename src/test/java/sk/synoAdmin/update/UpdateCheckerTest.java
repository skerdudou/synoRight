package sk.synoAdmin.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import sk.synoAdmin.UserException;

class UpdateCheckerTest {

	private static final String JSON = """
			{"tag_name":"v0.4","html_url":"https://github.com/skerdudou/synoRight/releases/tag/v0.4",
			 "assets":[
			  {"name":"synoRight-0.4.zip","browser_download_url":"https://example.com/synoRight-0.4.zip","size":9,"digest":"sha256:ffff"},
			  {"name":"synoRight-0.4-setup.exe","browser_download_url":"https://example.com/synoRight-0.4-setup.exe","size":1234,"digest":"sha256:ab12"},
			  {"name":"synoRight-0.4-windows-x64.zip","browser_download_url":"https://example.com/synoRight-0.4-windows-x64.zip","size":5678,"digest":"sha256:cd34"}
			 ]}""";

	@Test
	void parseFullReleaseWithSetupAndZip() {
		final Release release = Release.parse(JSON);
		assertEquals("0.4", release.version());
		assertEquals("https://github.com/skerdudou/synoRight/releases/tag/v0.4", release.pageUrl());
		assertEquals(Optional.of(new Release.Asset("https://example.com/synoRight-0.4-setup.exe", 1234, Optional.of("ab12"))),
				release.setup());
		assertEquals(Optional.of(new Release.Asset("https://example.com/synoRight-0.4-windows-x64.zip", 5678, Optional.of("cd34"))),
				release.zip());
	}

	@Test
	void parseAssetWithoutShaDigestHasNoChecksum() {
		final Release release = Release.parse("""
				{"tag_name":"0.5","html_url":"u","assets":[
				 {"name":"synoRight-0.5-setup.exe","browser_download_url":"s","size":10},
				 {"name":"synoRight-0.5-windows-x64.zip","browser_download_url":"z","size":20,"digest":"md5:12"}]}""");
		assertEquals(Optional.of(new Release.Asset("s", 10, Optional.empty())), release.setup());
		assertEquals(Optional.of(new Release.Asset("z", 20, Optional.empty())), release.zip());
	}

	@Test
	void parseWithoutAssets() {
		final Release release = Release.parse("{\"tag_name\":\"v0.4\",\"html_url\":\"u\"}");
		assertEquals(Optional.empty(), release.setup());
		assertEquals(Optional.empty(), release.zip());
	}

	@Test
	void parseRejectsMissingTag() {
		assertThrows(RuntimeException.class, () -> Release.parse("{\"html_url\":\"u\"}"));
		assertThrows(RuntimeException.class, () -> Release.parse("{\"tag_name\":\"  \"}"));
		assertThrows(RuntimeException.class, () -> Release.parse("{\"tag_name\":\"v\"}"));
	}

	@Test
	void parseIgnoresAssetWithoutUrlOrSize() {
		final Release release = Release.parse("""
				{"tag_name":"0.5","html_url":"u","assets":[
				 {"name":"synoRight-0.5-setup.exe","browser_download_url":" ","size":10,"digest":"sha256:ab"},
				 {"name":"synoRight-0.5-windows-x64.zip","browser_download_url":"z","digest":"sha256:ab"}]}""");
		assertEquals(Optional.empty(), release.setup());
		assertEquals(Optional.empty(), release.zip());
	}

	@Test
	void dueForCheckWhenLastCheckInFuture() {
		final Instant now = Instant.parse("2026-10-25T12:00:00Z");
		assertTrue(UpdateChecker.dueForCheck(Optional.of(now.plus(Duration.ofHours(1))), now));
	}

	@Test
	void parseKeepsSuffixAndNoLeadingV() {
		assertEquals("0.5-beta", Release.parse("{\"tag_name\":\"0.5-beta\",\"html_url\":\"u\"}").version());
	}

	@Test
	void newerComparesVersions() {
		final Release r = new Release("0.4", "u", Optional.empty(), Optional.empty());
		assertTrue(UpdateChecker.newer(r, "0.3"));
		assertFalse(UpdateChecker.newer(r, "0.4"));
		assertFalse(UpdateChecker.newer(new Release("0.4-beta", "u", Optional.empty(), Optional.empty()), "0.4"));
	}

	@Test
	void dueForCheck() {
		final Instant now = Instant.parse("2026-10-25T12:00:00Z");
		assertTrue(UpdateChecker.dueForCheck(Optional.empty(), now));
		assertFalse(UpdateChecker.dueForCheck(Optional.of(now.minus(Duration.ofHours(23))), now));
		assertTrue(UpdateChecker.dueForCheck(Optional.of(now.minus(Duration.ofHours(24))), now));
	}

	@Test
	void checkAndRecordRecordsDateAfterSuccess() {
		final Instant now = Instant.parse("2026-10-26T10:00:00Z");
		final List<Instant> recorded = new ArrayList<>();
		final Release release = new Release("0.4", "u", Optional.empty(), Optional.empty());
		assertSame(release, UpdateChecker.checkAndRecord(() -> release, recorded::add, () -> now));
		assertEquals(List.of(now), recorded);
	}

	@Test
	void checkAndRecordRecordsDateEvenWhenCheckFails() {
		final Instant now = Instant.parse("2026-10-26T10:00:00Z");
		final List<Instant> recorded = new ArrayList<>();
		final UserException failure = new UserException("boom");
		final UserException thrown = assertThrows(UserException.class, () -> UpdateChecker.checkAndRecord(() -> {
			throw failure;
		}, recorded::add, () -> now));
		assertSame(failure, thrown);
		assertEquals(List.of(now), recorded);
	}

	@Test
	void parseLastCheck() {
		final String out = "\r\nHKEY_CURRENT_USER\\Software\\synoRight\\Update\r\n    LastCheck    REG_SZ    1761393600\r\n";
		assertEquals(Optional.of(Instant.ofEpochSecond(1761393600L)), UpdateChecker.parseLastCheck(out));
		assertEquals(Optional.empty(), UpdateChecker.parseLastCheck("    LastCheck    REG_SZ    abc"));
		assertEquals(Optional.empty(), UpdateChecker.parseLastCheck(""));
		assertEquals(Optional.empty(), UpdateChecker.parseLastCheck(null));
	}

	@Test
	void fetchLatestAgainstLocalServer() throws IOException {
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/ok", exchange -> {
			final byte[] body = JSON.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.createContext("/html", exchange -> {
			final byte[] body = "<html>".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.createContext("/missing", exchange -> {
			exchange.sendResponseHeaders(404, -1);
			exchange.close();
		});
		server.start();
		try {
			final String base = "http://127.0.0.1:" + server.getAddress().getPort();
			assertEquals("0.4", UpdateChecker.fetchLatest(URI.create(base + "/ok")).version());
			assertThrows(UserException.class, () -> UpdateChecker.fetchLatest(URI.create(base + "/missing")));
			final UserException invalid = assertThrows(UserException.class, () -> UpdateChecker.fetchLatest(URI.create(base + "/html")));
			assertEquals("GitHub returned an unexpected response to the update check.", invalid.getMessage());
		} finally {
			server.stop(0);
		}
	}
}
