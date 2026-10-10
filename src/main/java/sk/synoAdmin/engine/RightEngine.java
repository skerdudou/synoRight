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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import sk.synoAdmin.ConsoleUtil;
import sk.synoAdmin.LogUtil;
import sk.synoAdmin.UserException;
import sk.synoAdmin.engine.DeltaResolver.GrpDelta;
import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.synology.SynoApiClient;
import sk.synoAdmin.synology.SynoFolderPermission;

public class RightEngine {

	// profondeur d'arborescence gérée (albums de niveau 1 et 2, comme getAccessRight côté API)
	private static final int MAX_DEPTH = 2;

	private final Preferences pref;
	private final SynoApiClient synoClient;

	private final List<Folder> folders = new ArrayList<>();

	public RightEngine(final Preferences pref) {
		this.pref = pref;
		synoClient = new SynoApiClient(pref);
	}

	public void getFullState() {
		// vérifié avant le login : pas d'erreur pendant une éventuelle saisie de mot de passe/OTP
		checkNasFolder();

		// lance le login Syno en asynchrone
		final CompletableFuture<Void> futureLogin = synoClient.loginAsynch();

		// access FS infos
		LogUtil.runMonitored(this::readFs, "Fs time");

		// wait for login
		try {
			futureLogin.get();
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		} catch (final ExecutionException e) {
			// remonte l'erreur du login telle quelle (message utilisateur, pas de stack trace)
			if (e.getCause() instanceof final RuntimeException cause) {
				throw cause;
			}
			throw new RuntimeException(e.getCause());
		}

		// access DB infos
		LogUtil.runMonitored(this::readDb, "Db time");
	}

	private void checkNasFolder() {
		if (!new File(pref.getNasFolder()).isDirectory()) {
			throw new UserException("Répertoire des photos inaccessible : '" + pref.getNasFolder()
					+ "'. Le NAS est-il joignable et le partage monté ?");
		}
	}

	private void readFs() {
		scanFolder(0, new File(pref.getNasFolder()), null);
	}

	private void scanFolder(final int level, final File folder, final Folder parent) {
		if (level == MAX_DEPTH) {
			return;
		}

		final File[] subFolders = folder.listFiles(File::isDirectory);
		if (subFolders == null) {
			throw new UserException("Impossible de lister le répertoire '" + folder.getPath() + "'");
		}

		for (final File file : subFolders) {
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
		final List<String> unknownInFs = new ArrayList<>();

		for (final SynoFolderPermission synoFolderRight : synoClient.getAccessRight()) {
			final Optional<Folder> folder = foldersToScan.stream()
					.filter(f -> f.getName().equals(synoFolderRight.name()))
					.findAny();
			if (folder.isEmpty()) {
				unknownInFs.add(synoFolderRight.name());
				continue;
			}
			updateDbInfos(folder.get(), synoFolderRight);
			foldersToScan.remove(folder.get());
		}

		// dossiers exclus de la synchro : avertissement plutôt que blocage
		if (!foldersToScan.isEmpty()) {
			System.out.println("Attention, dossiers pas encore indexés par Synology Photos (ignorés) : "
					+ foldersToScan.stream().map(Folder::getName).collect(Collectors.joining(", ")));
			folders.removeAll(foldersToScan);
		}
		if (!unknownInFs.isEmpty()) {
			System.out.println("Attention, dossiers connus de Synology Photos mais absents du partage (ignorés) : "
					+ String.join(", ", unknownInFs));
		}
	}

	private void updateDbInfos(final Folder folder, final SynoFolderPermission synoRight) {
		folder.setId(synoRight.id());
		for (final var grpId : synoRight.browseGrpIds()) {
			pref.getGroupById(grpId.toString())
					.ifPresent(folder::addDbGroup);
		}
	}

	public void login() {
		synoClient.login();
	}

	/**
	 * Affiche les écarts entre les fichiers de droits et Synology Photos, sans rien modifier.
	 */
	public void status() {
		final Map<Group, GrpDelta> grpDeltas = DeltaResolver.resolveDeltasFsToDb(folders);
		if (grpDeltas.isEmpty()) {
			System.out.println("Fichiers de droits et Synology Photos sont synchronisés (" + folders.size() + " dossiers).");
			return;
		}

		for (final Group group : pref.getGroups()) {
			final GrpDelta grpDelta = grpDeltas.get(group);
			if (grpDelta == null) {
				continue;
			}
			System.out.println("Groupe '" + group.getName() + "' :");
			printFolders("Dans les fichiers mais pas dans Synology Photos :", grpDelta.getToAdd());
			printFolders("Dans Synology Photos mais pas dans les fichiers :", grpDelta.getToRemove());
		}
		System.out.println("-> 'push' applique les fichiers dans Synology Photos, 'pull' recrée les fichiers d'après Synology Photos.");
	}

	private static void printFolders(final String message, final List<Folder> list) {
		if (!list.isEmpty()) {
			System.out.println("\t" + message);
			for (final Folder folder : list) {
				System.out.println("\t\t" + folder.getName());
			}
		}
	}

	// créé les fichiers sur le NAS en fonction des droits de Synology Photos
	public void dbToFs(final boolean assumeYes, final boolean dryRun) {
		System.out.println("Mise à jour des fichiers de droits d'après Synology Photos.");

		final Map<Group, GrpDelta> grpDeltas = DeltaResolver.resolveDeltasDbToFs(folders);
		if (!printAndConfirmDeltas(grpDeltas, assumeYes, dryRun)) {
			return;
		}

		int count = 0;
		for (final Group group : pref.getGroups()) {
			final GrpDelta grpDelta = grpDeltas.get(group);
			if (grpDelta == null) {
				continue;
			}
			for (final Folder folder : grpDelta.getToAdd()) {
				count += doCreateRightFile(group, folder) ? 1 : 0;
			}
			for (final Folder folder : grpDelta.getToRemove()) {
				count += doRemoveRightFile(group, folder) ? 1 : 0;
			}
		}
		System.out.println(count + " fichier(s) de droits mis à jour.");
	}

	// true si le fichier a été créé
	private boolean doCreateRightFile(final Group group, final Folder folder) {
		final File fileToAdd = getRightFile(group, folder);
		try {
			final boolean created = fileToAdd.createNewFile();
			Files.setAttribute(fileToAdd.toPath(), "dos:hidden", Boolean.TRUE, LinkOption.NOFOLLOW_LINKS);
			return created;
		} catch (final IOException e) {
			System.err.println("Impossible de créer le fichier d'affectation des droits " + fileToAdd.getPath());
			return false;
		}
	}

	// true si le fichier a été supprimé
	private boolean doRemoveRightFile(final Group group, final Folder folder) {
		final File fileToDelete = getRightFile(group, folder);
		if (!fileToDelete.exists()) {
			return false;
		}
		if (!fileToDelete.delete()) {
			System.err.println("Impossible de supprimer le fichier d'affectation des droits " + fileToDelete.getPath());
			return false;
		}
		return true;
	}

	private File getRightFile(final Group group, final Folder folder) {
		return new File(folder.getFolderPath(), group.getFileName());
	}

	// Mets a jour les droits dans Synology Photos en fonction des fichiers sur le NAS
	public void fsToDb(final boolean assumeYes, final boolean dryRun) {
		System.out.println("Mise à jour des droits dans Synology Photos d'après les fichiers.");

		final Map<Group, GrpDelta> grpDeltas = DeltaResolver.resolveDeltasFsToDb(folders);
		if (!printAndConfirmDeltas(grpDeltas, assumeYes, dryRun)) {
			return;
		}

		for (final Group group : pref.getGroups()) {
			final GrpDelta grpDelta = grpDeltas.get(group);
			if (grpDelta == null) {
				continue;
			}
			// add download role (or remove)
			synoClient.setAccessRight(group.getId(), grpDelta.getToAdd(), grpDelta.getToRemove());
		}
		System.out.println("Droits Synology Photos mis à jour.");
	}

	// affiche le détail des deltas puis demande confirmation avant application
	private boolean printAndConfirmDeltas(final Map<Group, GrpDelta> grpDeltas, final boolean assumeYes, final boolean dryRun) {
		if (grpDeltas.isEmpty()) {
			System.out.println("Aucune modification à appliquer.");
			return false;
		}

		for (final Group group : pref.getGroups()) {
			final GrpDelta grpDelta = grpDeltas.get(group);
			if (grpDelta != null) {
				System.out.println("Groupe '" + group.getName() + "' :");
				grpDelta.printAdd();
				grpDelta.printRemove();
			}
		}

		if (dryRun) {
			System.out.println("Simulation : aucune modification effectuée.");
			return false;
		}
		if (!assumeYes && !ConsoleUtil.promptBool("Appliquer ces modifications ?")) {
			System.out.println("Abandon, aucune modification effectuée.");
			return false;
		}
		return true;
	}

	public void addRightToFolder(final String folder, final Group grp) {
		final Folder folderToUpdate = resolveFolder(folder);
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

		System.out.println("Droit '" + grp.getName() + "' ajouté sur " + folderToUpdate.getName()
				+ (parentFolder == null ? "" : " (et sur le dossier parent " + parentFolder.getName() + ")") + ".");
	}

	public void removeRightToFolder(final String folder, final Group grp) {
		final Folder folderToUpdate = resolveFolder(folder);

		// suppression sur DB
		synoClient.removeAccessRight(grp.getId(), folderToUpdate);

		// suppression sur FS
		doRemoveRightFile(grp, folderToUpdate);

		System.out.println("Droit '" + grp.getName() + "' retiré de " + folderToUpdate.getName() + ".");
	}

	/**
	 * Affiche les droits d'un dossier côté Synology Photos et côté fichiers.
	 */
	public void showFolder(final String folder) {
		final Folder resolved = resolveFolder(folder);

		System.out.println("Dossier " + resolved.getName() + " :");
		boolean gap = false;
		for (final Group grp : pref.getGroups()) {
			final boolean inDb = resolved.getDbGroups().contains(grp);
			final boolean inFs = resolved.getFsGroups().contains(grp);
			gap |= inDb != inFs;
			System.out.println("\t" + grp.getName() + " : Synology Photos " + (inDb ? "oui" : "non")
					+ ", fichier " + (inFs ? "oui" : "non") + (inDb != inFs ? "   <- écart" : ""));
		}
		if (gap) {
			System.out.println("Écarts détectés : 'push' ou 'pull' pour resynchroniser.");
		}
	}

	/**
	 * Vérifie qu'un dossier est gérable (existe, sous le dossier photos) ; à appeler
	 * avant le login pour éviter une connexion inutile.
	 *
	 * @return son chemin relatif au dossier photos
	 */
	public Path checkFolder(final String folder) {
		final Path folderPath = Paths.get(folder).toAbsolutePath().normalize();
		final Path nasPath = Paths.get(pref.getNasFolder()).toAbsolutePath().normalize();

		if (!Files.isDirectory(folderPath)) {
			throw new UserException("Le répertoire '" + folder + "' n'existe pas.");
		}

		if (folderPath.equals(nasPath) || !folderPath.startsWith(nasPath)) {
			throw new UserException("Répertoire non pris en charge, il doit être contenu dans '" + pref.getNasFolder() + "'");
		}

		return nasPath.relativize(folderPath);
	}

	private Folder resolveFolder(final String folder) {
		final Path nasPath = Paths.get(pref.getNasFolder()).toAbsolutePath().normalize();
		final Path relativePath = checkFolder(folder);

		// résolution arbo des droits FS et DB en même temps que l'on avance dans l'arborescence Synology
		Folder parent = null;
		Integer parentId = null; // id Synology du dossier parent (null pour la racine)
		for (final Path currentPath : relativePath) {
			final File absoluteFilePath = parent == null ? nasPath.resolve(currentPath).toFile() : Paths.get(parent.getFolderPath()).resolve(currentPath).toFile();
			final Folder newFolder = new Folder(absoluteFilePath, parent);
			updateFsRight(newFolder);
			folders.add(newFolder);
			// Récupération des droits Synology à ce niveau
			boolean found = false;
			for (final var perm : synoClient.listFolderPermissions(parentId)) {
				if (perm.name().equals(newFolder.getName())) {
					updateDbInfos(newFolder, perm);
					parentId = perm.id();
					found = true;
					break;
				}
			}
			if (!found) {
				throw new UserException("Le dossier '" + newFolder.getName() + "' n'est pas (encore) indexé par Synology Photos.");
			}
			parent = newFolder;
		}

		return parent;
	}

}
