package sk.synoAdmin.engine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.synology.SynoApiClient;
import sk.synoAdmin.synology.pojo.SynoPermissionResponse;

public class RightEngine {

	private final Preferences pref;
	private final SynoApiClient synoClient = new SynoApiClient();

	private final List<Folder> folders = new ArrayList<>();

	public RightEngine(final Preferences pref) {
		this.pref = pref;

		synoClient.login(pref.getUser(), pref.getPassword());
	}

	public void init() {
		final long start = System.nanoTime();

		readFs();

		final long endFs = System.nanoTime();
		System.out.println("Fs time : " + (endFs - start) / 1000000);

		readDb();

		final long endDb = System.nanoTime();
		System.out.println("Db time : " + (endDb - endFs) / 1000000);
	}

	public void dbToFs() {
		for (final Folder folder : folders) {
			final List<Group> grpToRemove = new ArrayList<>(folder.getFsGroups());
			grpToRemove.removeAll(folder.getDbGroups());

			final List<Group> grpToAdd = new ArrayList<>(folder.getDbGroups());
			grpToAdd.removeAll(folder.getFsGroups());

			if (grpToRemove.size() > 0 || grpToAdd.size() > 0) {
				System.out.println("Répertoire '" + folder.getName() + "'");
				if (grpToRemove.size() > 0) {
					System.out.println("\tSuppression de :");
					for (final Group grp : grpToRemove) {
						System.out.println("\t\t" + grp.getName());
						final File fileToDelete = new File(folder.getFolder().getAbsolutePath() + "/" + grp.getFileName());
						if (!fileToDelete.delete()) {
							System.out.println("Impossible de supprimer le fichier d'affectation des droits " + fileToDelete.getPath());
						}
					}
				}
				if (grpToAdd.size() > 0) {
					System.out.println("\tAjout de :");
					for (final Group grp : grpToAdd) {
						System.out.println("\t\t" + grp.getName());
						final File fileToAdd = new File(folder.getFolder().getAbsolutePath() + "/" + grp.getFileName());
						try {
							fileToAdd.createNewFile();
							Files.setAttribute(fileToAdd.toPath(), "dos:hidden", Boolean.TRUE, LinkOption.NOFOLLOW_LINKS);
						} catch (final IOException e) {
							System.out.println("Impossible de créer le fichier d'affectation des droits " + fileToAdd.getPath());
						}
					}
				}
			}
		}
	}

	private void readFs() {
		final File root = new File(pref.getRootPath());

		scanFolder(0, root, null);
	}

	private void scanFolder(final int level, final File folder, final Folder parent) {
		if (level == 2) {
			return;
		}

		for (final File file : folder.listFiles(File::isDirectory)) {
			final Folder newFolder = new Folder(file, parent);
			updateFsRight(newFolder);
			folders.add(newFolder);

			scanFolder(level + 1, file, newFolder);
		}
	}

	private void updateFsRight(final Folder folder) {
		for (final File file : folder.getFolder().listFiles(Group.FILE_FILTER)) {
			pref.getGroups().stream()
					.filter(g -> g.match(file))
					.findAny()
					.ifPresent(g -> folder.addFsGroup(g));
		}

	}

	private void readDb() {
		for (final Group grp : pref.getGroups()) {
			final List<Folder> foldersToScan = new ArrayList<>(folders);

			for (final SynoPermissionResponse synoFolderRight : synoClient.getAccessRight(grp.getId()).getAll()) {
				if (synoFolderRight.getParent() == null) {
					// on exclue le premier niveau "Albums"
					continue;
				}

				final Optional<Folder> folder = foldersToScan.stream()
						.filter(f -> f.getName().equals(synoFolderRight.getTitle()))
						.filter(f -> f.matchParentId(synoFolderRight.getParent()))
						.findAny();

				if (!folder.isPresent()) {
					throw new RuntimeException("Impossible de matcher les droits de '" + synoFolderRight.getTitle() + "' avec le fileSystem");
				}

				foldersToScan.remove(folder.get());

				updateDbInfos(folder.get(), synoFolderRight, grp);
			}

			if (foldersToScan.size() > 0) {
				throw new RuntimeException("Certains éléments ne sont pas encore indexés");
			}

		}

	}

	private void updateDbInfos(final Folder folder, final SynoPermissionResponse synoRight, final Group grp) {
		folder.setId(synoRight.getId());
		if (synoRight.isBrowse()) {
			folder.addDbGroup(grp);
		}
	}

}