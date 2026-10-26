# Aperçu des photos — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** bandeau de 10 aperçus (vignettes Synology Photos, repli fichiers, sous-dossiers) dans « Droits du dossier », aperçu agrandi au clic, commande de diagnostic.

**Architecture:** le client API gagne la liste des éléments d'un dossier et le téléchargement d'une vignette ; un sélecteur pur choisit au plus 10 éléments (API puis sous-dossiers puis fichiers du partage) ; un chargeur produit l'image de chaque élément (vignette, fichier réduit ou icône) ; la fenêtre affiche un bandeau Swing alimenté en arrière-plan et un dialogue d'aperçu agrandi.

**Tech Stack:** Java 25, Swing/FlatLaf, Jackson 3, `java.net.http`, `javax.imageio`, JUnit 6, `FakeDsm` (faux NAS des tests).

**Spec:** `docs/superpowers/specs/2026-10-26-apercu-photos-design.md` (à lire avant chaque tâche).

## Global Constraints

- Fichiers suivis en **CRLF** (vérifier avec `file` ; `sed -i` sous Git Bash peut retirer les CR : reconvertir avec `sed -i 's/\r*$/\r/' f`). Commentaires, Javadoc et commits **en français**, densité comme le code voisin ; commits terminés par une ligne vide puis `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Textes visibles par `Messages.get(...)`, chaque clé dans les **5** fichiers `src/main/resources/i18n/messages{,_fr,_de,_es,_it}.properties` (anglais = référence ; `MessagesTest` vérifie mêmes clés et mêmes `{n}`) ; tests en anglais.
- Pas de réseau réel dans les tests : `FakeDsm` (loopback) seulement.
- Limites de la spec : **10** éléments par dossier ; vignette `sm` (~96 px) pour le bandeau, `xl` pour l'aperçu agrandi ; repli fichier si JPEG/PNG/GIF/BMP et **≤ 20 Mo** pour le bandeau (pas de limite pour l'aperçu agrandi, mais lecture sous-échantillonnée) ; `@eaDir` et fichiers cachés ignorés.
- Un aperçu ne doit jamais bloquer ni retarder le tableau des droits, ni ouvrir de boîte d'erreur.
- `mvn -B -q verify` depuis la racine du worktree ; commandes git simples et séparées (pas de chaînes complexes), jamais de push, de `git stash` ni de changement de branche.

## Review Focus

- Dossier connu de Photos mais photos pas encore indexées : le bandeau se remplit avec les fichiers du partage (test tâche 2).
- Changement rapide de ligne en multi-dossiers : seul le dernier dossier s'affiche, pas de vignettes d'un autre dossier mélangées (test du compteur de génération, tâche 4).
- Vignette pas prête / réponse JSON au lieu d'une image : repli fichier puis icône, sans exception (tests tâches 1 et 3).
- Grosse photo (> 20 Mo) ou HEIC sans vignette : icône, pas de lecture du fichier (test tâche 3).
- Dossier vide et sous-dossiers vides : message « aucune photo », pas d'erreur (test tâche 2).

---

### Task 1: API — éléments d'un dossier et vignettes

**Files:**
- Create: `src/main/java/sk/synoAdmin/synology/SynoPhotoItem.java`
- Modify: `src/main/java/sk/synoAdmin/synology/SynoApiClient.java`
- Modify: `src/test/java/sk/synoAdmin/FakeDsm.java`
- Test: `src/test/java/sk/synoAdmin/synology/SynoApiClientPhotosTest.java` (ou le test existant du client s'il y en a un)

**Interfaces:**
- Produces:
  - `record SynoPhotoItem(int id, String fileName, String type, Optional<Thumb> thumb)` avec `record Thumb(int unitId, String cacheKey, Set<String> readySizes)` imbriqué ; `boolean ready(String size)`.
  - `SynoApiClient.listItems(int folderId, int limit): List<SynoPhotoItem>` — `SYNO.FotoTeam.Browse.Item`, `method=list`, `version` = constante `ITEM_API_VERSION = "1"`, `folder_id`, `offset=0`, `limit`, `additional=["thumbnail"]` ; échec API ⇒ `UserException` (`synology.photos.readError` « Could not read the photos of the folder{0} » avec le suffixe d'erreur habituel) ; parse dans `static List<SynoPhotoItem> parseItemList(JsonNode)` (taille prête = valeur `"ready"` sous `additional.thumbnail.<taille>`).
  - `SynoApiClient.thumbnail(int unitId, String cacheKey, String size): Optional<byte[]>` — `SYNO.FotoTeam.Thumbnail`, `method=get`, `version` = constante `THUMBNAIL_API_VERSION = "2"`, `mode=download`, `id`, `type=unit`, `size`, `cache_key`, envoyé en POST comme les autres appels (en-têtes cookie et `X-SYNO-TOKEN`) ; vide si statut ≠ 200, si le `Content-Type` n'est pas `image/*`, ou sur erreur réseau (journal `LogUtil.debug`).
  - `FakeDsm.photo(int folderId, int id, String fileName, boolean thumbReady)` : déclare un élément ; `Browse.Item` répond la liste du dossier (`limit` respecté) avec `additional.thumbnail` (`unit_id` = id, `cache_key` = `id_1`, `sm`/`xl` = `ready` si `thumbReady`, sinon `"broken"`) ; `Thumbnail` répond un petit JPEG valide (généré avec `ImageIO`, 4×4) avec `Content-Type: image/jpeg` si l'élément a sa vignette prête, sinon la réponse JSON d'erreur habituelle.

- [ ] **Step 1: Tests** — `parseItemList` sur un JSON réaliste (deux éléments : un avec `sm`/`xl` ready, un sans `additional.thumbnail`) ⇒ champs corrects, `thumb` vide pour le second ; `listItems` contre `FakeDsm` (3 photos, `limit` 2 ⇒ 2 éléments) ; `thumbnail` prête ⇒ octets décodables par `ImageIO.read` ; vignette non prête ⇒ `Optional.empty()` ; `listItems` en échec API ⇒ `UserException`.
- [ ] **Step 2:** `mvn -B -q test -Dtest=SynoApiClientPhotosTest` ⇒ échec de compilation.
- [ ] **Step 3:** implémenter (méthode privée d'envoi qui renvoie les octets et le `Content-Type`, sans dupliquer la construction des en-têtes de `doRequest` : factoriser la préparation de la requête).
- [ ] **Step 4:** `mvn -B -q verify` ⇒ succès.
- [ ] **Step 5:** commit « API Synology Photos : éléments d'un dossier et vignettes ».

### Task 2: Choix des éléments à prévisualiser

**Files:**
- Create: `src/main/java/sk/synoAdmin/preview/PreviewItem.java`, `src/main/java/sk/synoAdmin/preview/PreviewPicker.java`
- Test: `src/test/java/sk/synoAdmin/preview/PreviewPickerTest.java`

**Interfaces:**
- Consumes: `SynoPhotoItem` (tâche 1).
- Produces:
  - `record PreviewItem(String fileName, Path file, Optional<SynoPhotoItem.Thumb> thumb)` — `file` = chemin local attendu (dossier local + nom), `thumb` vide pour un élément venu du partage.
  - `PreviewPicker.MAX = 10`.
  - `interface PhotoSource { List<SynoPhotoItem> items(int folderId, int limit); List<SynoFolderPermission> subfolders(int folderId); }` (implémentée par un adaptateur sur `SynoApiClient` : `listItems`, `listFolderPermissions`).
  - `static List<PreviewItem> pick(int folderId, Path localFolder, PhotoSource source)` :
    1. parcours en largeur des dossiers Photos à partir de `folderId` (le dossier puis ses sous-dossiers dans l'ordre renvoyé), `items(id, MAX - déjà choisis)`, chemin local d'un sous-dossier = dossier local parent + dernier segment de `SynoFolderPermission.name()` (après le dernier `/`) ;
    2. si moins de `MAX`, complément par les fichiers du partage : `localFolder` puis ses sous-dossiers en largeur, fichiers triés par nom (insensible à la casse), extensions `jpg jpeg png gif bmp heic heif tif tiff webp dng cr2 cr3 nef arw raf orf rw2 mp4 mov m4v avi mkv 3gp` (insensible à la casse), dossiers `@eaDir` et éléments cachés (nom commençant par `.` ou attribut caché) ignorés, sans doublon avec un élément déjà choisi (même chemin local, comparaison insensible à la casse) ;
    3. arrêt dès `MAX` éléments ; dossier local illisible ⇒ pas de complément (journal debug).
  - Une `UserException` de `source` remonte (le chargeur l'affiche comme « aperçu indisponible »).

- [ ] **Step 1: Tests** (`PhotoSource` en mémoire, dossiers temporaires `@TempDir`) — 12 photos API dans le dossier ⇒ 10, toutes avec `thumb` ; 3 photos dans le dossier + 9 dans un sous-dossier ⇒ 3 puis 7 du sous-dossier avec chemins locaux corrects ; API vide (indexation en cours) + 4 fichiers locaux (dont `.cache.jpg`, `@eaDir/x.jpg`, `notes.txt`) ⇒ 2 fichiers attendus seulement (`a.JPG`, `b.heic`), triés ; API 2 éléments + mêmes fichiers en local ⇒ pas de doublon ; dossier vide sans sous-dossier ⇒ liste vide ; complément local qui descend dans un sous-dossier quand le dossier n'a pas assez de fichiers.
- [ ] **Step 2:** tests ⇒ échec. **Step 3:** implémenter. **Step 4:** `mvn -B -q verify`. **Step 5:** commit « Aperçu des photos : choix des éléments (Photos, sous-dossiers, fichiers du partage) ».

### Task 3: Images des aperçus et commande de diagnostic

**Files:**
- Create: `src/main/java/sk/synoAdmin/preview/PreviewImages.java`, `src/main/java/sk/synoAdmin/preview/PreviewLoader.java`
- Create: `src/main/java/sk/synoAdmin/cli/PreviewsCommand.java` (enregistrée dans `SynoRightCommand`, `hidden = true`)
- Modify: 5 `messages*.properties`
- Test: `src/test/java/sk/synoAdmin/preview/PreviewImagesTest.java`, `src/test/java/sk/synoAdmin/preview/PreviewLoaderTest.java`

**Interfaces:**
- Consumes: tâches 1 et 2 ; `RightEngine.folderId(String)`, `RightEngine.checkFolder(String)`, `RightEngine.login()`.
- Produces:
  - `PreviewImages.THUMB_SIZE = 96`, `MAX_FILE_BYTES = 20L * 1024 * 1024`.
  - `static boolean readable(Path file)` (extension jpg/jpeg/png/gif/bmp, insensible à la casse).
  - `static Optional<BufferedImage> read(Path file, int maxSide, long maxBytes)` : vide si non lisible par extension, absent, > `maxBytes` (`Long.MAX_VALUE` = sans limite) ou décodage impossible ; lecture via `ImageReader` avec `ImageReadParam.setSourceSubsampling` calculé pour ne pas décoder plus de ~2× `maxSide`, puis réduction de qualité (`Graphics2D`, interpolation bilinéaire) à `maxSide` sur le plus grand côté ; jamais d'exception.
  - `static Optional<BufferedImage> decode(byte[] bytes, int maxSide)`.
  - `record Preview(PreviewItem item, Optional<BufferedImage> image, Source source, String reason)` avec `enum Source { THUMBNAIL, FILE, ICON }` (`reason` : texte court pour le diagnostic, vide si vignette).
  - `PreviewLoader(SynoApiClient client)` ; `Preview load(PreviewItem item, boolean large)` : `large=false` ⇒ vignette `sm` puis fichier (≤ 20 Mo) puis icône ; `large=true` ⇒ vignette `xl` puis fichier sans limite de taille (maxSide = taille demandée par l'appelant via `load(item, maxSide)`, à toi de choisir une signature claire) puis icône. Interface fonctionnelle injectable pour la vignette (`(unitId, cacheKey, size) -> Optional<byte[]>`) afin de tester sans client.
  - CLI `synoRight previews <dossier>` (cachée) : login, `folderId`, `PreviewPicker.pick`, puis `load` de chaque élément ; affiche une ligne par élément `source  nom  (raison)` puis `n éléments en x ms` ; textes `cli.previews.*` (EN de référence, à traduire) ; dossier hors périmètre ⇒ erreur habituelle de `checkFolder`.

- [ ] **Step 1: Tests** — `read` d'un JPEG 400×200 temporaire avec maxSide 96 ⇒ 96×48 ; fichier > `maxBytes` ⇒ vide sans lecture ; `.heic` ⇒ vide ; fichier corrompu ⇒ vide ; `decode` d'octets non image ⇒ vide ; `PreviewLoader` : vignette prête ⇒ `THUMBNAIL` ; vignette absente + fichier JPEG ⇒ `FILE` ; vignette absente + HEIC ⇒ `ICON` ; `large=true` et fichier de 25 Mo simulé (fonction de taille injectable ou vrai fichier creux via `RandomAccessFile.setLength` — vérifier que la lecture reste sous-échantillonnée) ⇒ image produite ; aide de `previews` absente de l'aide générale (cachée) mais `previews --help` fonctionne.
- [ ] **Step 2:** tests ⇒ échec. **Step 3:** implémenter. **Step 4:** `mvn -B -q verify`. **Step 5:** commit « Aperçu des photos : images (vignette, fichier réduit, icône) et commande de diagnostic ».

### Task 4: Bandeau et aperçu agrandi dans « Droits du dossier »

**Files:**
- Create: `src/main/java/sk/synoAdmin/gui/PreviewStrip.java`, `src/main/java/sk/synoAdmin/gui/PreviewDialog.java`
- Modify: `src/main/java/sk/synoAdmin/gui/RightsWindow.java`, `src/main/java/sk/synoAdmin/gui/FoldersRightsTableModel.java` (accès au dossier d'une ligne, ex. `String folderAt(int row)`), 5 `messages*.properties`
- Test: `src/test/java/sk/synoAdmin/gui/PreviewStripTest.java` (logique non graphique)

**Interfaces:**
- Consumes: `PreviewPicker`, `PreviewLoader`, `PreviewImages` (tâches 2-3), `RightEngine.folderId`.
- Produces / décisions :
  - `PreviewStrip` (`JPanel`) : `show(String folder)` / `clear()` ; un seul fil de chargement (exécuteur démon) par fenêtre, `dispose()` l'arrête (appelé à la fermeture de la fenêtre) ; compteur de génération : un résultat d'une génération dépassée est ignoré ; cache `Map<String, List<Preview>>` par chemin de dossier (seulement les dossiers entièrement chargés) ; cases d'attente (taille `THUMB_SIZE`), puis images au fil de l'eau (mises à jour sur l'EDT) ; icône neutre + nom (tronqué, info-bulle complète) pour `ICON` ; textes `gui.previews.selectFolder`, `gui.previews.empty`, `gui.previews.unavailable`, `gui.previews.loading` ; connexion : un `SynoApiClient(pref)` créé et `login()` à la première demande dans le fil de chargement (session existante réutilisée), `folderId` via un `RightEngine(pref)` connecté (ou équivalent, sans dupliquer la résolution des dossiers) ; toute exception ⇒ `gui.previews.unavailable` + `LogUtil.debug`.
  - Clic sur une vignette ⇒ `PreviewDialog` modal : image `large` chargée en arrière-plan (la vignette du bandeau affichée agrandie en attendant), taille max 80 % de l'écran, titre = nom du fichier, ← → pour l'élément précédent/suivant du bandeau, Échap ou clic pour fermer.
  - `RightsWindow` : bandeau sous le tableau (dans `content`, entre la table et `bottom`) ; un dossier : `show` après le premier affichage des droits ; plusieurs dossiers : `ListSelectionListener` sur la table (ligne sélectionnée ⇒ `show(folderAt(row))`, aucune ⇒ `selectFolder`) ; hauteur des fenêtres + hauteur du bandeau, largeur ≥ 720 pour un dossier ; fermeture de la fenêtre ⇒ `strip.dispose()`.
  - Le chargement des aperçus n'utilise ni `Gui.runAsync` ni la garde de fermeture (il ne doit ni griser la fenêtre ni empêcher sa fermeture).

- [ ] **Step 1: Tests** — logique extraite testable sans affichage (ex. classe interne/package-private de suivi de génération et de cache) : une génération dépassée est ignorée ; un dossier déjà chargé est servi depuis le cache sans nouvel appel ; un échec de chargement donne l'état « indisponible » ; `FoldersRightsTableModel.folderAt` ; `MessagesTest` vert.
- [ ] **Step 2:** tests ⇒ échec. **Step 3:** implémenter. **Step 4:** `mvn -B -q verify` (tests IHM existants verts). **Step 5:** commit « Droits du dossier : bandeau d'aperçu des photos et aperçu agrandi ».
