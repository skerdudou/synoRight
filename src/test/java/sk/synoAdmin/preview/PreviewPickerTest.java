package sk.synoAdmin.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sk.synoAdmin.preview.PreviewPicker.PhotoSource;
import sk.synoAdmin.synology.SynoFolderPermission;
import sk.synoAdmin.synology.SynoPhotoItem;

class PreviewPickerTest {
	@TempDir
	Path tmp;

	private static SynoPhotoItem photo(final int id, final String name) {
		return new SynoPhotoItem(id, name, "photo",
				Optional.of(new SynoPhotoItem.Thumb(id, "key" + id, Set.of("sm", "xl"))));
	}

	/** Source en mémoire : photos et sous-dossiers par id de dossier. */
	private static final class FakeSource implements PhotoSource {
		final Map<Integer, List<SynoPhotoItem>> items = new HashMap<>();
		final Map<Integer, List<SynoFolderPermission>> subs = new HashMap<>();

		@Override
		public List<SynoPhotoItem> items(final int folderId, final int limit) {
			final List<SynoPhotoItem> all = items.getOrDefault(folderId, List.of());
			return all.subList(0, Math.min(limit, all.size()));
		}

		@Override
		public List<SynoFolderPermission> subfolders(final int folderId) {
			return subs.getOrDefault(folderId, List.of());
		}
	}

	private static List<SynoPhotoItem> photos(final int firstId, final int count, final String prefix) {
		final List<SynoPhotoItem> list = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			list.add(photo(firstId + i, prefix + i + ".jpg"));
		}
		return list;
	}

	private static List<String> names(final List<PreviewItem> items) {
		return items.stream().map(PreviewItem::fileName).toList();
	}

	private void touch(final String relative) throws IOException {
		final Path p = tmp.resolve(relative);
		Files.createDirectories(p.getParent());
		Files.write(p, new byte[] { 1 });
	}

	@Test
	void capsAtTenWithThumbs() {
		final FakeSource src = new FakeSource();
		src.items.put(1, photos(100, 12, "p"));

		final List<PreviewItem> picked = PreviewPicker.pick(1, tmp, src);

		assertEquals(10, picked.size());
		assertTrue(picked.stream().allMatch(i -> i.thumb().isPresent()));
		assertEquals(tmp.resolve("p0.jpg"), picked.get(0).file());
	}

	@Test
	void descendsIntoSubfolderWithLocalPaths() {
		final FakeSource src = new FakeSource();
		src.items.put(1, photos(100, 3, "p"));
		src.subs.put(1, List.of(new SynoFolderPermission(2, "/2024/vacances/plage", List.of())));
		src.items.put(2, photos(200, 9, "s"));

		final List<PreviewItem> picked = PreviewPicker.pick(1, tmp, src);

		assertEquals(10, picked.size());
		assertEquals(List.of("p0.jpg", "p1.jpg", "p2.jpg", "s0.jpg", "s1.jpg", "s2.jpg", "s3.jpg", "s4.jpg",
				"s5.jpg", "s6.jpg"), names(picked));
		assertEquals(tmp.resolve("plage").resolve("s6.jpg"), picked.get(9).file());
	}

	@Test
	void emptyApiFallsBackToShareFiles() throws IOException {
		touch("b.heic");
		touch("a.JPG");
		touch(".cache.jpg");
		touch("@eaDir/x.jpg");
		touch("notes.txt");

		final List<PreviewItem> picked = PreviewPicker.pick(1, tmp, new FakeSource());

		assertEquals(List.of("a.JPG", "b.heic"), names(picked));
		assertTrue(picked.stream().allMatch(i -> i.thumb().isEmpty()));
	}

	@Test
	void noDuplicateBetweenApiAndShare() throws IOException {
		touch("a.jpg");
		touch("b.jpg");
		touch("c.jpg");
		final FakeSource src = new FakeSource();
		src.items.put(1, List.of(photo(1, "A.JPG"), photo(2, "b.jpg")));

		final List<PreviewItem> picked = PreviewPicker.pick(1, tmp, src);

		assertEquals(List.of("A.JPG", "b.jpg", "c.jpg"), names(picked));
	}

	@Test
	void emptyFolderGivesEmptyList() {
		assertTrue(PreviewPicker.pick(1, tmp, new FakeSource()).isEmpty());
	}

	@Test
	void shareCompletionDescendsIntoSubfolders() throws IOException {
		touch("a.jpg");
		touch("sub/b.png");
		touch("sub/c.png");

		final List<PreviewItem> picked = PreviewPicker.pick(1, tmp, new FakeSource());

		assertEquals(List.of("a.jpg", "b.png", "c.png"), names(picked));
		assertEquals(tmp.resolve("sub").resolve("b.png"), picked.get(1).file());
	}

	@Test
	void unreadableLocalFolderGivesNoCompletion() {
		assertTrue(PreviewPicker.pick(1, tmp.resolve("missing"), new FakeSource()).isEmpty());
	}
}
