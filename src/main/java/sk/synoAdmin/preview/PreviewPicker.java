package sk.synoAdmin.preview;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

import sk.synoAdmin.LogUtil;
import sk.synoAdmin.synology.SynoApiClient;
import sk.synoAdmin.synology.SynoFolderPermission;
import sk.synoAdmin.synology.SynoPhotoItem;

/**
 * Choisit les (au plus {@link #MAX}) éléments à prévisualiser pour un dossier : d'abord ceux de Synology Photos
 * (dossier puis sous-dossiers en largeur), puis les fichiers du partage si Photos n'en a pas assez
 * (par exemple indexation en cours).
 */
public final class PreviewPicker {
	public static final int MAX = 10;

	private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "bmp", "heic", "heif", "tif",
			"tiff", "webp", "dng", "cr2", "cr3", "nef", "arw", "raf", "orf", "rw2", "mp4", "mov", "m4v", "avi", "mkv",
			"3gp");

	/**
	 * Accès à Synology Photos (remplaçable en test).
	 */
	public interface PhotoSource {
		List<SynoPhotoItem> items(int folderId, int limit);

		List<SynoFolderPermission> subfolders(int folderId);

		static PhotoSource of(final SynoApiClient client) {
			return new PhotoSource() {
				@Override
				public List<SynoPhotoItem> items(final int folderId, final int limit) {
					return client.listItems(folderId, limit);
				}

				@Override
				public List<SynoFolderPermission> subfolders(final int folderId) {
					return client.listFolderPermissions(folderId);
				}
			};
		}
	}

	private record Node(int id, Path local) {
	}

	private PreviewPicker() {
		// utility
	}

	/**
	 * Une {@code UserException} de la source remonte à l'appelant.
	 */
	public static List<PreviewItem> pick(final int folderId, final Path localFolder, final PhotoSource source) {
		final List<PreviewItem> picked = new ArrayList<>();
		final Set<String> seen = new HashSet<>();

		// Photos : le dossier puis ses sous-dossiers en largeur
		final Queue<Node> queue = new ArrayDeque<>();
		queue.add(new Node(folderId, localFolder));
		while (!queue.isEmpty() && picked.size() < MAX) {
			final Node node = queue.remove();
			for (final SynoPhotoItem item : source.items(node.id(), MAX - picked.size())) {
				final Path file = node.local().resolve(item.fileName());
				if (picked.size() < MAX && seen.add(key(file))) {
					picked.add(new PreviewItem(item.fileName(), file, item.thumb()));
				}
			}
			if (picked.size() < MAX) {
				for (final SynoFolderPermission sub : source.subfolders(node.id())) {
					final String name = sub.name();
					queue.add(new Node(sub.id(), node.local().resolve(name.substring(name.lastIndexOf('/') + 1))));
				}
			}
		}

		if (picked.size() < MAX) {
			completeFromShare(localFolder, picked, seen);
		}
		return picked;
	}

	/**
	 * Complète avec les fichiers du partage : dossier local puis sous-dossiers en largeur, noms triés.
	 */
	private static void completeFromShare(final Path localFolder, final List<PreviewItem> picked,
			final Set<String> seen) {
		final Queue<Path> dirs = new ArrayDeque<>();
		dirs.add(localFolder);
		while (!dirs.isEmpty() && picked.size() < MAX) {
			final Path dir = dirs.remove();
			final List<Path> entries = new ArrayList<>();
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
				stream.forEach(entries::add);
			} catch (final IOException | RuntimeException e) {
				LogUtil.debug("Local folder unreadable " + dir + " : " + e);
				continue;
			}
			entries.sort(Comparator.comparing((final Path p) -> p.getFileName().toString().toLowerCase(Locale.ROOT))
					.thenComparing(p -> p.getFileName().toString()));
			for (final Path entry : entries) {
				final String name = entry.getFileName().toString();
				if (name.startsWith(".") || isHidden(entry)) {
					continue;
				}
				if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
					if (!name.equals("@eaDir")) {
						dirs.add(entry);
					}
				} else if (picked.size() < MAX && Files.isRegularFile(entry) && hasMediaExtension(name)
						&& seen.add(key(entry))) {
					picked.add(new PreviewItem(name, entry, Optional.empty()));
				}
			}
		}
	}

	private static boolean hasMediaExtension(final String name) {
		final int dot = name.lastIndexOf('.');
		return dot >= 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	private static boolean isHidden(final Path path) {
		try {
			return Files.isHidden(path);
		} catch (final IOException e) {
			return false;
		}
	}

	private static String key(final Path file) {
		return file.toString().toLowerCase(Locale.ROOT);
	}
}
