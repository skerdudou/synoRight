package sk.synoAdmin.synology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.ObjectMapper;

import sk.synoAdmin.FakeDsm;
import sk.synoAdmin.UserException;
import sk.synoAdmin.prefs.Session;

class SynoApiClientPhotosTest {

	@TempDir
	Path tempDir;

	private FakeDsm dsm;
	private SynoApiClient client;

	@BeforeEach
	void setUp() throws IOException {
		dsm = new FakeDsm()
				.photo(7, 101, "a.jpg", true)
				.photo(7, 102, "b.jpg", false)
				.photo(7, 103, "c.jpg", true);
		client = new SynoApiClient(dsm.url(), "admin", "pwd", false, Session.load(tempDir), null);
		client.login();
	}

	@AfterEach
	void tearDown() {
		dsm.close();
	}

	@Test
	void parseItemList_tailleDeVignettePrete() {
		final List<SynoPhotoItem> items = SynoApiClient.parseItemList(new ObjectMapper().readTree("""
				{"success":true,"data":{"list":[
					{"id":5,"filename":"a.jpg","type":"photo","additional":{"thumbnail":{"unit_id":5,"cache_key":"5_1","sm":"ready","xl":"ready","m":"broken"}}},
					{"id":6,"filename":"b.mp4","type":"video"}
				]}}"""));

		assertEquals(2, items.size());
		assertEquals(5, items.get(0).id());
		assertEquals("a.jpg", items.get(0).fileName());
		assertEquals("photo", items.get(0).type());
		assertEquals(new SynoPhotoItem.Thumb(5, "5_1", Set.of("sm", "xl")), items.get(0).thumb().orElseThrow());
		assertTrue(items.get(0).ready("sm"));
		assertFalse(items.get(0).ready("m"));
		assertEquals("video", items.get(1).type());
		assertTrue(items.get(1).thumb().isEmpty());
		assertFalse(items.get(1).ready("sm"));
	}

	@Test
	void listItems_respecteLaLimite() {
		assertEquals(3, client.listItems(7, 10).size());
		assertEquals(2, client.listItems(7, 2).size());
	}

	@Test
	void listItems_echecDeLApi() {
		dsm.failListings(1);

		final UserException e = assertThrows(UserException.class, () -> client.listItems(7, 10));

		assertTrue(e.getMessage().contains("117"), e.getMessage());
	}

	@Test
	void thumbnail_prete() throws IOException {
		final Optional<byte[]> bytes = client.thumbnail(101, "101_1", "sm");

		final BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes.orElseThrow()));
		assertEquals(4, image.getWidth());
	}

	@Test
	void thumbnail_nonPrete() {
		assertTrue(client.thumbnail(102, "102_1", "sm").isEmpty());
	}
}
