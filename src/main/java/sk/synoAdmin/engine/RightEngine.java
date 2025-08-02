package sk.synoAdmin.engine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import sk.synoAdmin.LogUtil;
import sk.synoAdmin.UserException;
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

	public void getFullState() {
		// Lauch login to Syno asynch
		final CompletableFuture<Void> futureLogin = synoClient.loginAsynch();

		// access FS infos
		LogUtil.runMonitored(this::readFs, "Fs time");

		// wait for login
		try {
			futureLogin.get();
		} catch (InterruptedException | ExecutionException e) {
			throw new RuntimeException(e);
		}

		// access DB infos
		LogUtil.runMonitored(this::readDb, "Db time");
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
		final List<Folder> foldersToScan = new ArrayList<>(folders);

		for (final SynoPermissionResponse synoFolderRight : synoClient.getAccessRight()) {
			final Folder folder = findFolder(foldersToScan, synoFolderRight);
			updateDbInfos(folder, synoFolderRight);

			foldersToScan.remove(folder);
		}

		if (foldersToScan.size() > 0) {
			throw new RuntimeException("Certains éléments ne sont pas encore indexés");
		}
	}

	private Folder findFolder(final List<Folder> foldersToScan, final SynoPermissionResponse synoFolderRight) {
		final Optional<Folder> folder = findFolderOptional(foldersToScan, synoFolderRight);

		if (!folder.isPresent()) {
			throw new RuntimeException("Impossible de matcher les droits de '" + synoFolderRight.getName() + "' avec le fileSystem");
		}
		return folder.get();
	}

	private Optional<Folder> findFolderOptional(final List<Folder> foldersToScan, final SynoPermissionResponse synoFolderRight) {
		return foldersToScan.stream()
				.filter(f -> f.getName().equals(synoFolderRight.getName()))
				.findAny();
	}

	private void updateDbInfos(final Folder folder, final SynoPermissionResponse synoRight) {
		folder.setId(synoRight.getId());
		for (var grpId : synoRight.getBrowseGrpIds()) {
			pref.getGroupById(grpId.toString())
					.ifPresent(folder::addDbGroup);
		}
	}

	public void login() {
		synoClient.login();
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
		if (fileToDelete.exists() && !fileToDelete.delete()) {
			System.out.println("Impossible de supprimer le fichier d'affectation des droits " + fileToDelete.getPath());
		}
	}

	private File getRightFile(final Group group, final Folder folder) {
		return new File(folder.getFolderPath() + "/" + group.getFileName());
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

			// add download role (or remove)
			synoClient.setAccessRight(group.getId(), grpDelta.getToAdd(), grpDelta.getToRemove());
		}
	}

	public void addRightToFolder(final String folder, final Group grp) {
		final Folder folderToUpdate = getFolderToUpdate(folder, grp);
		final Folder parentFolder = folderToUpdate.getParent();

		// ajout sur DB
		if (parentFolder == null) {
			synoClient.addAccessRight(grp.getId(), folderToUpdate);
		} else {
			synoClient.addAccessRight(grp.getId(), folderToUpdate, parentFolder);
		}

		// ajout sur FS
		doCreateRightFile(grp, folderToUpdate);
		if (parentFolder != null) {
			doCreateRightFile(grp, parentFolder);
		}

	}

	public void removeRightToFolder(final String folder, final Group grp) {
		final Folder folderToUpdate = getFolderToUpdate(folder, grp);

		// ajout sur DB
		synoClient.removeAccessRight(grp.getId(), folderToUpdate);

		// ajout sur FS
		doRemoveRightFile(grp, folderToUpdate);
	}

	private Folder getFolderToUpdate(final String folder, final Group grp) {
		final Path folderPath = Paths.get(folder);
		final Path nasPath = Paths.get(pref.getNasFolder());

		if (!Files.isDirectory(folderPath)) {
			throw new UserException("Le répertoire '" + folder + "' n'existe pas.");
		}

		if (folderPath.equals(nasPath) || !folderPath.startsWith(nasPath)) {
			throw new UserException("Répertoire non pris en charge, il doit être contenu dans '" + pref.getNasFolder() + "'");
		}

		final Path relativePath = nasPath.relativize(folderPath);

		// résolution arbo des droits FS et DB en même temps que l'on avance dans l'arborescence Synology
		Folder parent = null;
		Integer parentId = null; // id Synology du dossier parent (null pour la racine)
		for (final Path curentPath : relativePath) {
			final File absoluteFilePath = parent == null ? nasPath.resolve(curentPath).toFile() : Paths.get(parent.getFolderPath()).resolve(curentPath).toFile();
			final Folder newFolder = new Folder(absoluteFilePath, parent);
			updateFsRight(newFolder);
			folders.add(newFolder);
			// Récupération des droits Synology à ce niveau
			var resp = synoClient.getSynoAccessRightResponse(parentId);
			boolean found = false;
			for (var perm : resp.getPermissions()) {
				if (perm.getName().equals(newFolder.getName())) {
					updateDbInfos(newFolder, perm);
					parentId = perm.getId();
					found = true;
					break;
				}
			}
			if (!found) {
				throw new RuntimeException("Impossible de trouver le dossier '" + newFolder.getName() + "' dans l'arborescence Synology");
			}
			parent = newFolder;
		}

		return parent;
	}

}
