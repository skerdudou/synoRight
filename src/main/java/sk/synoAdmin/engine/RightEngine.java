package sk.synoAdmin.engine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import sk.synoAdmin.LogUtil;
import sk.synoAdmin.UserException;
import sk.synoAdmin.engine.DeltaResolver.GrpDelta;
import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.synology.SynoApiClient;
import sk.synoAdmin.synology.SynoFolderPermission;

public class RightEngine {

	// profondeur d'arborescence gérée : Synology Photos ne gère les droits que sur les dossiers de niveau 1 et 2
	private static final int MAX_DEPTH = 2;

	private final Preferences pref;
	private final SynoApiClient synoClient;

	private final List<Folder> folders = new ArrayList<>();
	private final List<String> unindexedFolders = new ArrayList<>();
	private final List<String> unknownFolders = new ArrayList<>();

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
			throw new UserException(Messages.get("engine.nasFolder.inaccessible", pref.getNasFolder()));
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
			throw new UserException(Messages.get("engine.folder.listError", folder.getPath()));
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
			unindexedFolders.addAll(foldersToScan.stream().map(Folder::getName).toList());
			folders.removeAll(foldersToScan);
		}
		unknownFolders.addAll(unknownInFs);
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

	public SyncPlan planPush() {
		return plan(SyncPlan.Direction.PUSH, DeltaResolver.resolveDeltasFsToDb(folders));
	}

	public SyncPlan planPull() {
		return plan(SyncPlan.Direction.PULL, DeltaResolver.resolveDeltasDbToFs(folders));
	}

	private SyncPlan plan(final SyncPlan.Direction direction, final Map<Group, GrpDelta> deltas) {
		// ordre de la configuration, pour un affichage stable
		final Map<Group, GrpDelta> ordered = new LinkedHashMap<>();
		for (final Group group : pref.getGroups()) {
			if (deltas.containsKey(group)) {
				ordered.put(group, deltas.get(group));
			}
		}
		return new SyncPlan(direction, ordered, folders.size(), List.copyOf(unindexedFolders), List.copyOf(unknownFolders));
	}

	/**
	 * Applique un plan calculé par planPush/planPull.
	 *
	 * @return nombre de modifications effectuées (fichiers créés/supprimés, ou droits Photos modifiés)
	 */
	public int apply(final SyncPlan plan) {
		int count = 0;
		for (final Map.Entry<Group, GrpDelta> entry : plan.deltas().entrySet()) {
			final Group group = entry.getKey();
			final GrpDelta delta = entry.getValue();
			if (plan.direction() == SyncPlan.Direction.PUSH) {
				// add download role (or remove)
				synoClient.setAccessRight(group.getId(), delta.getToAdd(), delta.getToRemove());
				count += delta.getToAdd().size() + delta.getToRemove().size();
			} else {
				for (final Folder folder : delta.getToAdd()) {
					count += doCreateRightFile(group, folder) ? 1 : 0;
				}
				for (final Folder folder : delta.getToRemove()) {
					count += doRemoveRightFile(group, folder) ? 1 : 0;
				}
			}
		}
		return count;
	}

	// true si le fichier a été créé
	private boolean doCreateRightFile(final Group group, final Folder folder) {
		final File fileToAdd = getRightFile(group, folder);
		try {
			final boolean created = fileToAdd.createNewFile();
			Files.setAttribute(fileToAdd.toPath(), "dos:hidden", Boolean.TRUE, LinkOption.NOFOLLOW_LINKS);
			return created;
		} catch (final IOException e) {
			System.err.println(Messages.get("engine.file.createError", fileToAdd.getPath()));
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
			System.err.println(Messages.get("engine.file.deleteError", fileToDelete.getPath()));
			return false;
		}
		return true;
	}

	private File getRightFile(final Group group, final Folder folder) {
		return new File(folder.getFolderPath(), group.getFileName());
	}

	public String addRightToFolder(final String folder, final Group grp) {
		return addRight(resolveFolder(folder), grp);
	}

	public String removeRightToFolder(final String folder, final Group grp) {
		return removeRight(resolveFolder(folder), grp);
	}

	// ajoute le droit côté Photos et fichiers (parent compris), renvoie le message de confirmation
	private String addRight(final Folder folderToUpdate, final Group grp) {
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

		return parentFolder == null
				? Messages.get("engine.right.added", grp.getName(), folderToUpdate.getName())
				: Messages.get("engine.right.addedWithParent", grp.getName(), folderToUpdate.getName(), parentFolder.getName());
	}

	// retire le droit côté Photos et fichiers, renvoie le message de confirmation
	private String removeRight(final Folder folderToUpdate, final Group grp) {
		// suppression sur DB
		synoClient.removeAccessRight(grp.getId(), folderToUpdate);

		// suppression sur FS
		doRemoveRightFile(grp, folderToUpdate);

		return Messages.get("engine.right.removed", grp.getName(), folderToUpdate.getName());
	}

	public FolderRights folderRights(final String folder) {
		return folderRights(resolveFolder(folder, synoClient::listFolderPermissions));
	}

	/**
	 * Droits de plusieurs dossiers, dans l'ordre donné ; chaque liste de dossiers Synology Photos
	 * (racine, sous-dossiers d'un parent) n'est lue qu'une fois.
	 */
	public List<FolderRights> folderRights(final List<String> folderList) {
		final List<String> errors = new ArrayList<>();
		final Map<String, FolderRights> rights = folderRights(folderList, errors);
		if (!errors.isEmpty()) {
			throw new UserException(String.join("\n", errors));
		}
		return List.copyOf(rights.values());
	}

	/**
	 * Droits des dossiers lisibles, par dossier (chemin tel que reçu), dans l'ordre donné ; un dossier en erreur
	 * (hors du dossier photos, non indexé...) est ignoré et son erreur ajoutée à errors.
	 */
	public Map<String, FolderRights> folderRights(final List<String> folderList, final List<String> errors) {
		// HashMap : clé null acceptée pour la racine
		final Map<Integer, List<SynoFolderPermission>> listings = new HashMap<>();
		final Map<String, FolderRights> rights = new LinkedHashMap<>();
		for (final String folder : folderList) {
			try {
				rights.put(folder, folderRights(resolveFolder(folder, id -> listings.computeIfAbsent(id, synoClient::listFolderPermissions))));
			} catch (final UserException e) {
				errors.add(Messages.get("engine.folder.error", folder, e.getMessage()));
			}
		}
		return rights;
	}

	/**
	 * Dossiers gérables (voir {@link #checkFolder(String)}), vérifiés sans connexion ; les erreurs des autres sont
	 * ajoutées à errors. Aucun dossier gérable : erreur du premier.
	 */
	public List<String> checkFolders(final List<String> folderList, final List<String> errors) {
		final List<String> valid = new ArrayList<>();
		UserException first = null;
		for (final String folder : folderList) {
			try {
				checkFolder(folder);
				valid.add(folder);
			} catch (final UserException e) {
				first = first == null ? e : first;
				errors.add(Messages.get("engine.folder.error", folder, e.getMessage()));
			}
		}
		if (valid.isEmpty() && first != null) {
			throw first;
		}
		return valid;
	}

	private FolderRights folderRights(final Folder resolved) {
		final List<FolderRights.GroupRight> rights = pref.getGroups().stream()
				.map(g -> new FolderRights.GroupRight(g, resolved.getDbGroups().contains(g), resolved.getFsGroups().contains(g)))
				.toList();
		return new FolderRights(resolved.getName(), rights);
	}

	/**
	 * Donne le droit aux groupes voulus et le retire aux autres groupes gérés, côté Photos et fichiers.
	 *
	 * @return description des modifications effectuées (vide si le dossier était déjà conforme)
	 */
	public List<String> setFolderRights(final String folder, final Set<Group> wanted) {
		final Folder resolved = resolveFolder(folder);
		final List<String> messages = new ArrayList<>();
		for (final Group group : pref.getGroups()) {
			final boolean inDb = resolved.getDbGroups().contains(group);
			final boolean inFs = resolved.getFsGroups().contains(group);
			if (wanted.contains(group) && !(inDb && inFs)) {
				messages.add(addRight(resolved, group));
			} else if (!wanted.contains(group) && (inDb || inFs)) {
				messages.add(removeRight(resolved, group));
			}
		}
		return messages;
	}

	/**
	 * Applique les groupes voulus sur plusieurs dossiers, dossiers de premier niveau d'abord : un ajout
	 * sur un sous-dossier, qui donne aussi le droit au parent, n'est pas défait par le traitement du parent.
	 * Un dossier en erreur (non indexé...) n'empêche pas le traitement des autres.
	 *
	 * @return description des modifications effectuées et des erreurs, dossier par dossier
	 */
	public List<String> setFolderRights(final Map<String, Set<Group>> wantedByFolder) {
		final List<String> ordered = wantedByFolder.keySet().stream()
				// profondeur sans accès disque : un dossier disparu est signalé par forEachFolder, pas par le tri
				.sorted(Comparator.comparingInt(f -> Paths.get(f).toAbsolutePath().normalize().getNameCount()))
				.toList();
		return forEachFolder(ordered, f -> setFolderRights(f, wantedByFolder.get(f)));
	}

	/**
	 * Exécute l'action sur chaque dossier, dans l'ordre ; une erreur prévue sur un dossier est ajoutée
	 * aux messages (préfixée du dossier) sans empêcher le traitement des suivants.
	 */
	public static List<String> forEachFolder(final List<String> folderList, final Function<String, List<String>> action) {
		final List<String> messages = new ArrayList<>();
		for (final String folder : folderList) {
			try {
				messages.addAll(action.apply(folder));
			} catch (final UserException e) {
				messages.add(Messages.get("engine.folder.error", folder, e.getMessage()));
			}
		}
		return messages;
	}

	/** Id Synology Photos du dossier. */
	public int folderId(final String folder) {
		return resolveFolder(folder).getId();
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
			throw new UserException(Messages.get("engine.folder.notFound", folder));
		}

		if (folderPath.equals(nasPath) || !folderPath.startsWith(nasPath)) {
			throw new UserException(Messages.get("engine.folder.unsupported", pref.getNasFolder()));
		}

		final Path relativePath = nasPath.relativize(folderPath);
		if (relativePath.getNameCount() > MAX_DEPTH) {
			throw new UserException(Messages.get("engine.folder.depth", MAX_DEPTH, pref.getNasFolder()));
		}
		return relativePath;
	}

	private Folder resolveFolder(final String folder) {
		return resolveFolder(folder, synoClient::listFolderPermissions);
	}

	/**
	 * @param lister liste des dossiers Synology Photos sous un parent (null : racine)
	 */
	private Folder resolveFolder(final String folder, final Function<Integer, List<SynoFolderPermission>> lister) {
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
			for (final var perm : lister.apply(parentId)) {
				if (perm.name().equals(newFolder.getName())) {
					updateDbInfos(newFolder, perm);
					parentId = perm.id();
					found = true;
					break;
				}
			}
			if (!found) {
				throw new UserException(Messages.get("engine.folder.notIndexed", newFolder.getName()));
			}
			parent = newFolder;
		}

		return parent;
	}

}
