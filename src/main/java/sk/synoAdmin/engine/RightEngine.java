package sk.synoAdmin.engine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import sk.synoAdmin.engine.DeltaResolver.GrpDelta;
import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.synology.SynoApiClient;
import sk.synoAdmin.synology.pojo.SynoPermissionResponse;

public class RightEngine {

	private final Preferences pref;
	private final SynoApiClient synoClient;

	private final List<Folder> folders = new ArrayList<>();

	public RightEngine(final Preferences pref) {
		this.pref = pref;
		synoClient = new SynoApiClient(pref);
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

	private void readFs() {
		final File root = new File(pref.getNasFolder());

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
		for (final Group grp : pref.getGroups()) {
			final File fileToCheck = getRightFile(grp, folder);
			if (fileToCheck.exists()) {
				folder.addFsGroup(grp);
			}
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

	// créé les fichiers sur le NAS en fonction des droits de PhotoStation
	public void dbToFs() {
		System.out.println("Mise à jour des fichiers de droits sur le filesystem.");

		final Map<Group, GrpDelta> grpDeltas = DeltaResolver.resolveDeltasDbToFs(folders);
		for (final Entry<Group, GrpDelta> grpDeltaEntry : grpDeltas.entrySet()) {
			final Group group = grpDeltaEntry.getKey();
			final GrpDelta grpDelta = grpDeltaEntry.getValue();

			System.out.println("Groupe '" + group.getName() + "' :");

			grpDelta.printAndProcessAdd(f -> doCreateRightFile(group, f));
			grpDelta.printAndProcessRemove(f -> doRemoveRightFile(group, f));

		}

	}

	private void doCreateRightFile(final Group group, final Folder folder) {
		final File fileToAdd = getRightFile(group, folder);
		try {
			fileToAdd.createNewFile();
			Files.setAttribute(fileToAdd.toPath(), "dos:hidden", Boolean.TRUE, LinkOption.NOFOLLOW_LINKS);
		} catch (final IOException e) {
			System.out.println("Impossible de créer le fichier d'affectation des droits " + fileToAdd.getPath());
		}
	}

	private void doRemoveRightFile(final Group group, final Folder folder) {
		final File fileToDelete = getRightFile(group, folder);
		if (!fileToDelete.delete()) {
			System.out.println("Impossible de supprimer le fichier d'affectation des droits " + fileToDelete.getPath());
		}
	}

	// Mets a jour les droits dans PhotoStation en fonction des fichiers sur le NAS
	public void fsToDb() {
		System.out.println("Mise à jour des droits dans PhotoStation.");

		final Map<Group, GrpDelta> grpDeltas = DeltaResolver.resolveDeltasFsToDb(folders);
		for (final Entry<Group, GrpDelta> grpDeltaEntry : grpDeltas.entrySet()) {
			final Group group = grpDeltaEntry.getKey();
			final GrpDelta grpDelta = grpDeltaEntry.getValue();

			System.out.println("Groupe '" + group.getName() + "' :");

			grpDelta.printAndProcessAdd(null);
			grpDelta.printAndProcessRemove(null);

			synoClient.setAccessRight(group.getId(), grpDelta.getToAdd(), grpDelta.getToRemove());
		}
	}

	private File getRightFile(final Group group, final Folder folder) {
		return new File(folder.getFolderPath() + "/" + group.getFileName());
	}

}