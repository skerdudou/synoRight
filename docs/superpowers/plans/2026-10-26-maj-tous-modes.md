# Mise à jour dans tous les modes — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** mise à jour du zip portable par l'appli (script PowerShell), contrôle strict des téléchargements (digest obligatoire, taille), date de vérification enregistrée après toute vérification (stockage inchangé, registre).

**Architecture:** `Release` décrit deux assets (setup, zip) avec URL, taille et empreinte ; `Updater` choisit le mode (setup, portable, page), télécharge un asset en exigeant l'empreinte et la taille ; un nouveau `PortableUpdate` décompresse le zip en sécurité, vérifie qu'aucune autre instance ne tourne et lance la ressource `apply-update.ps1` ; la fenêtre de configuration enchaîne selon le mode.

**Tech Stack:** Java 25, Swing, `java.util.zip`, `ProcessHandle`, PowerShell 5.1 + robocopy, JUnit 6.

**Spec:** `docs/superpowers/specs/2026-10-26-maj-tous-modes-design.md` (à lire avant chaque tâche ; complète `2026-10-25-mises-a-jour-design.md`).

## Global Constraints

- Fichiers suivis en **CRLF** (vérifier avec `file` ; `sed -i` sous Git Bash peut retirer les CR : reconvertir) ; `apply-update.ps1` en **UTF-8 avec BOM** + CRLF. Commentaires, Javadoc et commits **en français** ; commits terminés par une ligne vide puis `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Textes visibles par `Messages.get(...)`, chaque clé dans les **5** fichiers `messages{,_fr,_de,_es,_it}.properties` ; tests en anglais.
- Pas de réseau réel dans les tests (serveur HTTP loopback autorisé). Le script PowerShell peut être exécuté par un test **seulement sous Windows** (`@EnabledOnOs(OS.WINDOWS)`), sur des dossiers temporaires, avec `-NoRelaunch`.
- Noms d'assets exacts : `synoRight-<version>-setup.exe`, `synoRight-<version>-windows-x64.zip` (dossier racine `synoRight/` contenant `synoRightGui.exe`).
- Installateur inchangé (utilisateur seul).
- `mvn -B -q verify` depuis la racine du worktree ; commandes git simples et séparées, jamais de push / stash / changement de branche ; vérifier que le commit existe.

## Review Focus

- Zip malveillant ou inattendu (entrée `../`, chemin absolu, pas de dossier `synoRight/`, pas d'exe) : refus, rien lancé (tests tâche 2).
- Fenêtre des droits ouverte depuis l'Explorateur pendant une mise à jour portable : refus avec message (test de la détection, tâche 2).
- Asset sans `digest` ou taille différente : refus, fichier supprimé (tests tâche 1).
- Hors ligne au démarrage : la date est enregistrée malgré l'échec, pas de nouvel essai avant 24 h (test tâche 1).
- Configuration posée à côté de l'exe dans un dossier portable : conservée par le script (test d'intégration tâche 2).

---

### Task 1: Assets, contrôle strict, modes et date de vérification

**Files:**
- Modify: `src/main/java/sk/synoAdmin/update/Release.java`, `Updater.java`, `UpdateChecker.java`
- Modify: `src/main/java/sk/synoAdmin/cli/UpdateCommand.java`, `src/main/java/sk/synoAdmin/gui/ConfigWindow.java` (appels existants seulement : nouvelles signatures, date après toute vérification ; le mode portable est branché en tâche 3)
- Modify: 5 `messages*.properties`
- Test: `src/test/java/sk/synoAdmin/update/UpdateCheckerTest.java`, `UpdaterTest.java`

**Interfaces:**
- Produces:
  - `record Release.Asset(String url, long size, Optional<String> sha256)` ; `record Release(String version, String pageUrl, Optional<Asset> setup, Optional<Asset> zip)` ; `Release.parse(json)` lit pour chaque asset `browser_download_url`, `size`, `digest` (`sha256:` ⇒ hex, sinon vide) ; noms exacts de la spec.
  - `enum Updater.Mode { INSTALL, PORTABLE, OPEN_PAGE }` ; `Updater.mode(Release r, Optional<Path> appDir)` : appDir présent + `unins000.exe` + setup ⇒ INSTALL ; appDir présent sans `unins000.exe` + zip + `synoRightGui.exe` dans appDir ⇒ PORTABLE ; sinon OPEN_PAGE. (`UpdateChecker.installedBySetup` peut rester comme aide.)
  - `Updater.download(Release.Asset asset, String fileName, Path dir, LongConsumer length, LongConsumer progress, BooleanSupplier cancelled): Path` (garder la surcharge package-private à délais injectables) : `sha256` vide ⇒ `UserException` `update.download.noDigest` **avant** tout téléchargement ; taille reçue ≠ `asset.size()` ⇒ fichier supprimé + `UserException` `update.download.badSize` ; empreinte ⇒ comme aujourd'hui (`badDigest`).
  - `UpdateChecker.lastCheck()` / `markChecked(Instant)` : inchangés (registre `HKCU\Software\synoRight\Update`, valeur `LastCheck`).
  - Appels : vérification automatique et manuelle (IHM) et CLI `update` enregistrent la date **après toute vérification, réussie ou non** (finally).

- [ ] **Step 1: Tests** — `parse` avec setup + zip (taille, digest) et un asset sans digest ⇒ `sha256` vide ; `mode` : `unins000.exe` + setup ⇒ INSTALL, dossier sans `unins000.exe` avec `synoRightGui.exe` + zip ⇒ PORTABLE, zip absent ⇒ OPEN_PAGE, appDir vide (jar) ⇒ OPEN_PAGE ; `download` : sans digest ⇒ `noDigest` et aucun appel au serveur (compteur du HttpServer loopback), taille annoncée différente ⇒ `badSize` et fichier absent ; date enregistrée même quand la vérification échoue (extraire la séquence vérifier-puis-enregistrer dans une méthode testable avec un enregistreur injecté, ex. `checkAndRecord(Supplier<Release>, Consumer<Instant>)`).
- [ ] **Step 2:** tests ⇒ échec. **Step 3:** implémenter (clés EN : `update.download.noDigest` « The update file has no published checksum: download refused. », `update.download.badSize` « The downloaded file does not have the expected size ({0} bytes instead of {1}). » ; traduire). **Step 4:** `mvn -B -q verify`. **Step 5:** commit « Mise à jour : empreinte obligatoire, contrôle de taille, date enregistrée après toute vérification ».

### Task 2: Mise à jour du zip portable (décompression, script, autres instances)

**Files:**
- Create: `src/main/java/sk/synoAdmin/update/PortableUpdate.java`
- Create: `src/main/resources/update/apply-update.ps1` (UTF-8 avec BOM, CRLF)
- Modify: 5 `messages*.properties`
- Test: `src/test/java/sk/synoAdmin/update/PortableUpdateTest.java`, `src/test/java/sk/synoAdmin/update/ApplyUpdateScriptTest.java` (Windows seulement)

**Interfaces:**
- Consumes: `Release.Asset`, `Updater.download` (tâche 1).
- Produces:
  - `static Path extract(Path zip, Path targetDir)` : décompresse, refuse toute entrée dont le chemin normalisé sort de `targetDir` (`UserException` `update.portable.badZip`) ; renvoie `targetDir/synoRight` après avoir vérifié qu'il contient `synoRightGui.exe` (sinon `badZip`).
  - `static List<ProcessHandle> otherInstances(Path appDir, long selfPid, Stream<ProcessHandle> processes)` : processus dont `info().command()` est sous `appDir` (comparaison de chemins normalisés, insensible à la casse), hors `selfPid` ; surcharge sans flux sur `ProcessHandle.allProcesses()`.
  - `static List<String> scriptCommand(Path script, long pid, Path appDir, Path newDir, Path relaunch, Path logDir)` : `powershell -NoProfile -ExecutionPolicy Bypass -File <script> -AppPid <pid> -AppDir <appDir> -NewDir <newDir> -Relaunch <relaunch> -LogDir <logDir>`.
  - `static void start(Path tempDir, Path newDir, Path appDir, Path logDir)` : copie la ressource `/update/apply-update.ps1` dans `tempDir`, lance `scriptCommand` détaché (`ProcessBuilder.start()`, sans attendre). L'appelant quitte ensuite.
  - Script `apply-update.ps1` (paramètres `-AppPid -AppDir -NewDir -Relaunch -LogDir [-CopyOnly] [-NoRelaunch]`) conforme à la spec : attente de `AppPid` et de tout processus dont `Path` est sous `AppDir` (60 s max, sinon journal « abandon » et relance sans rien copier) ; test d'écriture dans `AppDir` (création/suppression d'un fichier) ⇒ si refusé, `Start-Process powershell -Verb RunAs -Wait` sur lui-même avec `-CopyOnly` ; copie : `robocopy <NewDir>\app <AppDir>\app /MIR /R:2 /W:1 /NP /NJH /NJS`, idem `runtime`, puis `robocopy <NewDir> <AppDir> /LEV:1 /R:2 /W:1 /NP /NJH /NJS` (racine sans suppression) ; codes ≥ 8 journalisés ; relance de `-Relaunch` (sauf `-NoRelaunch` ou `-CopyOnly`) ; suppression du dossier parent de `NewDir` (dossier temporaire) en dernier (`Remove-Item -Recurse -Force`, erreurs ignorées) ; journal `update.log` dans `LogDir` (ajout, horodatage ISO, une ligne par étape, codes robocopy). Messages du journal en anglais (fichier technique).

- [ ] **Step 1: Tests** — `extract` : zip valide ⇒ chemin `…/synoRight` ; entrée `../evil.txt` ⇒ `badZip` et rien écrit hors du dossier ; entrée à chemin absolu ⇒ `badZip` ; pas de dossier `synoRight/` ou pas d'exe ⇒ `badZip` ; `otherInstances` sur des `ProcessHandle` simulés (sous-classe/stub ou interface de test) : processus sous appDir ⇒ trouvé, soi-même exclu, autre dossier ignoré, casse différente ⇒ trouvé ; `scriptCommand` exact ; **intégration Windows** : dossiers temporaires `app/` (avec `app/app/old.jar`, `app/runtime/x`, `app/synoRightGui.exe` « old », `app/config.synoRight.properties`, `app/synoRightMenu-0.2.dll`) et `new/synoRight/` (`app/new.jar`, `runtime/y`, `synoRightGui.exe` « new », `synoRightMenu-0.3.dll`) ; lancement de `powershell -File apply-update.ps1 -AppPid <pid d'un processus terminé> … -NoRelaunch` et attente ⇒ `app/app` contient seulement `new.jar`, `runtime` seulement `y`, `synoRightGui.exe` = « new », `config.synoRight.properties` et `synoRightMenu-0.2.dll` toujours là, `synoRightMenu-0.3.dll` copié, `update.log` écrit, dossier temporaire supprimé.
- [ ] **Step 2:** tests ⇒ échec. **Step 3:** implémenter (clés `update.portable.badZip` « The update archive is not valid: {0} », `update.portable.otherInstances` « Close the other synoRight windows before updating ({0} running). », `update.portable.launchFailed` « The update was downloaded but could not be applied: {0} »). **Step 4:** `mvn -B -q verify`. **Step 5:** commit « Mise à jour du zip portable : décompression contrôlée, script de remplacement, autres instances ».

### Task 3: Branchement dans la fenêtre de configuration et documentation

**Files:**
- Modify: `src/main/java/sk/synoAdmin/gui/ConfigWindow.java` (flux « Mettre à jour »)
- Modify: `README.md`, `README.fr.md`
- Modify: 5 `messages*.properties` si de nouveaux textes sont nécessaires
- Test: tests existants verts ; test unitaire de toute logique de décision extraite

**Interfaces:**
- Consumes: `Updater.mode(release, Launchers.appDir())`, `Updater.download(asset, fileName, …)`, `PortableUpdate.otherInstances/extract/start`.
- Décisions :
  - OPEN_PAGE ⇒ navigateur (inchangé).
  - INSTALL ⇒ flux actuel (garde `confirmClose`, téléchargement du setup avec progression/annulation, lancement, sortie), avec la nouvelle signature (asset `setup`, nom `synoRight-<v>-setup.exe`).
  - PORTABLE ⇒ d'abord `otherInstances(appDir, pid)` non vide ⇒ message `update.portable.otherInstances` (nombre) et arrêt ; puis garde `confirmClose` ; dossier `Files.createTempDirectory("synoRight-update-")` ; téléchargement du zip (même dialogue de progression/annulation) ; hors EDT : `extract`, `start(tempDir, newDir, appDir, configDir)` ; succès ⇒ `System.exit(0)` ; échec/annulation ⇒ dossier temporaire supprimé (récursif), message d'erreur (pas en cas d'annulation), la fenêtre reste ouverte.
  - INSTALL aussi : téléchargement dans un dossier temporaire dédié supprimé en cas d'échec/annulation.
  - « Mettre à jour » refusé quand la fenêtre est occupée (`busy`, message `gui.busy`) — c'est déjà le comportement de `confirmClose` : vérifier qu'il s'applique avant tout téléchargement dans les deux modes.
- README (fr, en), section Installation/mise à jour : le zip portable se met aussi à jour depuis l'appli (remplacement de `app`, `runtime` et des fichiers de l'appli, configuration posée à côté conservée, journal `update.log` dans le dossier de configuration, fermer les autres fenêtres synoRight) ; un téléchargement sans empreinte publiée est refusé.

- [ ] **Step 1: Tests** — extraire en logique testable (ex. méthode statique de décision) ce qui peut l'être ; `MessagesTest` vert.
- [ ] **Step 2:** tests ⇒ échec si nouvelle logique. **Step 3:** implémenter. **Step 4:** `mvn -B -q verify`. **Step 5:** commit « Fenêtre de configuration : mise à jour du zip portable ; documentation ».
