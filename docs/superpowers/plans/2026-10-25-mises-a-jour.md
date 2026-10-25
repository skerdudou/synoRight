# Mises à jour facilitées — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** menus de l'Explorateur réparés automatiquement après une mise à jour, installateur Inno Setup par utilisateur, détection et installation d'une nouvelle version depuis l'IHM.

**Architecture:** un repère d'installation dans `HKCU\Software\synoRight\Install` compare la version et le lanceur courants à ceux des menus installés ; `MenuRepair` réinstalle les menus présents (mise à jour sur place du package Windows 11, DLL au nom versionné). Un script Inno Setup emballe `target/dist/synoRight` et appelle `menu repair` / `menu uninstall`. `UpdateChecker` interroge l'API GitHub, l'IHM affiche un bandeau et lance le setup téléchargé.

**Tech Stack:** Java 25, picocli, Swing/FlatLaf, Jackson 3 (`tools.jackson`), `java.net.http`, reg.exe/PowerShell via `ProcessRunner`, Inno Setup 6, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-25-mises-a-jour-design.md` (à lire avant chaque tâche).

## Global Constraints

- Tous les fichiers suivis sont en **CRLF** (`core.autocrlf=false`) ; un fichier écrit par Write/sed sort en LF : le reconvertir avant commit (`sed -i 's/\r*$/\r/' f`, vérifier avec `file f`). `native/build.ps1` reste en UTF-8 **avec BOM**.
- Commentaires, Javadoc et messages de commit **en français** ; densité de commentaires comme le code voisin. Commits terminés par `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Tout texte visible passe par `Messages.get("clé", args…)` ; chaque nouvelle clé est ajoutée aux **5** fichiers `src/main/resources/i18n/messages{,_fr,_de,_es,_it}.properties` (anglais = référence ; `MessagesTest` vérifie mêmes clés et mêmes `{n}`). Les tests tournent en anglais.
- Pas d'exécution réelle de reg.exe, PowerShell ou réseau dans les tests : logique pure testée, appels système dans de petites méthodes non testées ou injectées (`Function`/`Supplier`).
- Version de l'application : `Implementation-Version` du manifeste (null en développement ⇒ ni réparation ni vérification de mise à jour).
- Dépôt GitHub : `skerdudou/synoRight` ; API `https://api.github.com/repos/skerdudou/synoRight/releases/latest`.
- Build et tests : `mvn -B -q verify` depuis la racine du worktree (Windows, Git Bash ou PowerShell).

## Review Focus

- Classique avec sélection multiple : 15 processus lancés en même temps après une mise à jour ⇒ un seul répare, les autres ne sont ni bloqués ni en erreur (test du verrou, tâche 4).
- Réparation qui échoue (UAC refusée, PowerShell en erreur) ⇒ pas de nouvelle tentative au lancement suivant pour la même version (test `AttemptedVersion`, tâche 4).
- Installation antérieure (0.3) sans repère mais avec menus ⇒ réparée, dossier de configuration retrouvé (tests de détection et de résolution du `ConfigDir`, tâche 4).
- Utilisateur ayant désinstallé le menu Windows 11 et gardé le classique ⇒ la réparation ne réinstalle que le classique (test de cible déduite, tâche 4).
- Réponse GitHub inattendue (pas d'asset setup, tag non numérique, version de pré-version) ⇒ pas de crash, ouverture de la page ou « à jour » (tests tâche 6).

---

### Task 1: Version de l'application et repère d'installation

**Files:**
- Create: `src/main/java/sk/synoAdmin/AppVersion.java`
- Create: `src/main/java/sk/synoAdmin/menu/InstallMarker.java`
- Modify: `src/main/java/sk/synoAdmin/cli/SynoRightCommand.java` (VersionProvider utilise `AppVersion.current()`)
- Modify: `src/main/java/sk/synoAdmin/menu/ModernMenuInstaller.java` (`uninstall` ne supprime plus que `HKCU\Software\synoRight\Menu`)
- Modify: `src/main/java/sk/synoAdmin/menu/MenuService.java` (`uninstall` supprime en plus `HKCU\Software\synoRight` entière si elle existe)
- Test: `src/test/java/sk/synoAdmin/AppVersionTest.java`, `src/test/java/sk/synoAdmin/menu/InstallMarkerTest.java`

**Interfaces:**
- Produces:
  - `AppVersion.current(): Optional<String>` (Implementation-Version de `AppVersion.class.getPackage()`).
  - `AppVersion.compare(String a, String b): int` — segments séparés par `.` comparés numériquement (segment absent = 0), suffixe après le premier `-` : une version avec suffixe est inférieure à la même sans suffixe, deux suffixes comparés en texte ; segment non numérique ⇒ comparé en texte après les numériques.
  - `record InstallMarker(String version, String launcher, String configDir, String certThumbprint, String attemptedVersion)` (champs null si absents) dans `sk.synoAdmin.menu` :
    - `static final String KEY = "HKEY_CURRENT_USER\\Software\\synoRight\\Install"`, `static final String ROOT_KEY = "HKEY_CURRENT_USER\\Software\\synoRight"` ;
    - `static Optional<InstallMarker> parse(String regQueryOutput)` (sortie de `reg query <KEY>`, lignes `    Nom    REG_SZ    valeur`, valeurs pouvant contenir des espaces) ;
    - `String toRegFile()` : contenu .reg (`[-KEY]` puis `[KEY]` et les valeurs non nulles, échappement `\\` et `\"`, fins de ligne `\r\n`) ;
    - `InstallMarker withAttemptedVersion(String v)` ;
    - `static Optional<InstallMarker> read()` (reg query, vide si clé absente ou hors Windows) ; `void write()` (via `ClassicMenuInstaller.importRegFile`).

- [ ] **Step 1: Tests** — `AppVersionTest` : `compare("0.10","0.9") > 0`, `compare("0.4","0.4.0") == 0`, `compare("0.4-beta","0.4") < 0`, `compare("0.4-beta","0.4-rc") < 0`, `compare("1.0","0.99") > 0`. `InstallMarkerTest` : `parse` d'une sortie réelle

```
HKEY_CURRENT_USER\Software\synoRight\Install
    Version    REG_SZ    0.4
    Launcher    REG_SZ    C:\Users\a b\AppData\Local\Programs\synoRight\synoRightGui.exe
    ConfigDir    REG_SZ    C:\Users\a b\.synoRight
```

donne version `0.4`, launcher avec espaces intact, certThumbprint null ; `parse("")` vide ; `toRegFile()` d'un repère complet contient `"Launcher"="C:\\Users\\a b\\…"` et commence par `Windows Registry Editor Version 5.00\r\n` ; aller-retour : valeurs nulles absentes du .reg.
- [ ] **Step 2:** `mvn -B -q test -Dtest=AppVersionTest,InstallMarkerTest` ⇒ échec de compilation.
- [ ] **Step 3:** implémenter ; `SynoRightCommand.VersionProvider` lit `AppVersion.current()` ; modifier les deux `uninstall`.
- [ ] **Step 4:** `mvn -B -q verify` ⇒ succès.
- [ ] **Step 5:** commit « Repère d'installation des menus et version de l'application ».

### Task 2: DLL du menu Windows 11 au nom versionné et nettoyage

**Files:**
- Modify: `native/build.ps1` (DLL copiée sous `synoRightMenu-$Version.dll` — version Maven telle quelle —, `@DLL@` remplacé dans le manifeste)
- Modify: `native/package/AppxManifest.xml` (`Path="@DLL@"` à la place de `synoRightMenu.dll`)
- Modify: `src/main/java/sk/synoAdmin/menu/ModernMenuInstaller.java`
- Test: `src/test/java/sk/synoAdmin/menu/ModernMenuInstallerTest.java`

**Interfaces:**
- Consumes: `AppVersion.current()`.
- Produces:
  - `ModernMenuInstaller.dllName(String version): String` ⇒ `"synoRightMenu-" + version + ".dll"` ; `availability()` vérifie `dllName(version courante)` (version absente ⇒ indisponible avec le message existant `menu.install.modern.needsApp`).
  - `ModernMenuInstaller.staleDlls(List<String> fileNames, String version): List<String>` : noms `synoRightMenu*.dll` (insensible à la casse) autres que `dllName(version)`, ancien `synoRightMenu.dll` compris.
  - `ModernMenuInstaller.deleteStaleDlls(Path appDir)` : supprime ces fichiers, ignore les échecs (`LogUtil.debug`).

- [ ] **Step 1: Tests** — `dllName("0.4") == "synoRightMenu-0.4.dll"` ; `staleDlls(List.of("synoRightMenu.dll","synoRightMenu-0.3.dll","synoRightMenu-0.4.dll","synoRight.msix","SYNORIGHTMENU-0.2.DLL"),"0.4")` = `["synoRightMenu.dll","synoRightMenu-0.3.dll","SYNORIGHTMENU-0.2.DLL"]`.
- [ ] **Step 2:** lancer les tests ⇒ échec.
- [ ] **Step 3:** implémenter ; adapter `build.ps1` (garder BOM + CRLF, message final avec le nom de la DLL) ; vérifier que `native/synoRightMenu/synoRightMenu.vcxproj` produit toujours `synoRightMenu.dll` dans `target/native` (le renommage se fait à la copie).
- [ ] **Step 4:** `mvn -B -q verify` ⇒ succès. Si VS Build Tools + SDK + certificat sont présents sur la machine, `mvn -B -q package -Pexe -DskipTests` ⇒ `target/dist/synoRight/synoRightMenu-<version>.dll` présent et `AppxManifest.xml` du package (`target/native/package`) contient ce nom ; sinon le signaler dans le rapport.
- [ ] **Step 5:** commit « Menu Windows 11 : DLL au nom versionné, anciennes DLL supprimées ».

### Task 3: Mise à jour sur place du package Windows 11

**Files:**
- Modify: `src/main/java/sk/synoAdmin/menu/ModernMenuInstaller.java`
- Test: `src/test/java/sk/synoAdmin/menu/ModernMenuInstallerTest.java`

**Interfaces:**
- Produces:
  - `record InstalledPackage(String version, String installLocation)` (imbriqué dans `ModernMenuInstaller`) ; `static Optional<InstalledPackage> installedPackage()` : PowerShell `Get-AppxPackage -Name synoRight | ForEach-Object { $_.Version; $_.InstallLocation }` (deux lignes), vide si rien.
  - `enum PackageAction { ADD, UPGRADE, REINSTALL }` ; `static PackageAction packageAction(Optional<InstalledPackage> installed, Path appDir, String packageVersion)` : absent ⇒ ADD ; même dossier (comparaison de chemins normalisés, insensible à la casse) et version installée strictement inférieure (`AppVersion.compare`) ⇒ UPGRADE ; sinon REINSTALL.
  - `static String packageVersion(String appVersion)` : même conversion que `build.ps1` (`0.4-SNAPSHOT` ⇒ `0.4.0.0`, `1.2.3` ⇒ `1.2.3.0`).
  - `install(...)` : REINSTALL ⇒ `removePackage()` puis `Add-AppxPackage` ; UPGRADE et ADD ⇒ `Add-AppxPackage` seul ; puis `deleteStaleDlls(appDir)` après l'enregistrement. Retourne l'empreinte du certificat approuvé : `static String install(...)` (signature existante, retour `String` au lieu de `void`).

- [ ] **Step 1: Tests** — `packageAction(Optional.empty(), …)` = ADD ; installé `0.3.0.0` dans `C:\Apps\synoRight`, appDir `c:\apps\synoright\`, version `0.4.0.0` ⇒ UPGRADE ; même version ⇒ REINSTALL ; autre dossier ⇒ REINSTALL ; version installée supérieure ⇒ REINSTALL. `packageVersion("0.4-SNAPSHOT") == "0.4.0.0"`.
- [ ] **Step 2:** tests ⇒ échec. **Step 3:** implémenter. **Step 4:** `mvn -B -q verify`. **Step 5:** commit « Menu Windows 11 : mise à jour du package sur place ».

### Task 4: Réparation des menus (détection, verrou, exécution) et repère écrit à l'installation

**Files:**
- Create: `src/main/java/sk/synoAdmin/menu/MenuRepair.java`
- Modify: `src/main/java/sk/synoAdmin/menu/MenuService.java` (`install` et `refresh` écrivent le repère : version courante, `CommandLines.join(Launchers.gui())`, `pref.getConfigDir()`, empreinte renvoyée par le menu Windows 11 ou celle du repère précédent, `attemptedVersion` = version)
- Test: `src/test/java/sk/synoAdmin/menu/MenuRepairTest.java`

**Interfaces:**
- Consumes: `InstallMarker`, `AppVersion`, `ModernMenuInstaller.install` (tâche 3), `MenuService.install(Preferences, MenuTarget)`.
- Produces (dans `MenuRepair`) :
  - `record InstalledMenus(boolean classic, boolean modernConfig)` : présence de la clé du menu classique et de `HKCU\Software\synoRight\Menu` (lecture `reg query`, pas de PowerShell : rapide).
  - `static boolean needsRepair(Optional<InstallMarker> marker, InstalledMenus menus, String version, String launcher, boolean force)` : faux si aucun menu ; vrai si `force` ; repère absent ⇒ vrai ; `attemptedVersion` égale à `version` et version ou lanceur différents ⇒ faux (déjà tenté) ; sinon vrai si version ou lanceur différent.
  - `static Path configDir(Optional<InstallMarker> marker, Optional<String> modernConfigDir, Optional<String> classicCommand)` : repère ⇒ son `configDir` ; sinon valeur `ConfigDir` de la clé Menu ; sinon argument suivant `-c` dans la commande du menu classique (`Directory\ContextMenus\SynoRight\shell\<première entrée>\command`, analysée avec les règles de `CommandLines`) ; sinon `Preferences.resolveConfigDir(null)`.
  - `static MenuTarget target(InstalledMenus menus)` : les deux ⇒ ALL, Menu seul ⇒ MODERN, classique seul ⇒ CLASSIC.
  - `enum Outcome { NOTHING, REPAIRED, BUSY }` ; `static Outcome repair(boolean force)` : version courante absente ⇒ NOTHING ; verrou `%TEMP%\synoRight-repair.lock` (`FileChannel.tryLock`) non obtenu ⇒ BUSY ; `needsRepair` faux ⇒ NOTHING ; sinon écrit d'abord le repère avec `attemptedVersion` = version (le reste inchangé), charge `Preferences` du `configDir`, `MenuService.install(pref, target(menus))` (qui réécrit le repère complet), REPAIRED. Les exceptions remontent à l'appelant après l'écriture d'`attemptedVersion`.
  - `static boolean isNeeded()` : `needsRepair(..., force=false)` avec les lectures réelles (pour l'IHM et `--if-needed`).

- [ ] **Step 1: Tests** — `needsRepair` : aucun menu ⇒ false même avec `force` ; repère absent + classique ⇒ true ; repère `0.3`, version `0.4` ⇒ true ; repère `0.4` même lanceur ⇒ false ; repère `0.4` lanceur différent ⇒ true ; repère `0.3` avec `attemptedVersion` `0.4`, version `0.4` ⇒ false ; même cas avec `force` ⇒ true. `configDir` : chaque source dans l'ordre de priorité, et `classicCommand` = `"C:\a b\synoRightGui.exe" -c "D:\conf dir" rights --collect "%V"` ⇒ `D:\conf dir`. `target` : les trois cas. Verrou : deux `FileChannel.tryLock` successifs sur le même fichier depuis `MenuRepair.tryLock(Path)` (méthode package-private renvoyant `Optional<FileLock>`) ⇒ le second vide tant que le premier est tenu (utiliser `OverlappingFileLockException` comme « non obtenu »).
- [ ] **Step 2:** tests ⇒ échec. **Step 3:** implémenter. **Step 4:** `mvn -B -q verify`. **Step 5:** commit « Réparation automatique des menus après une mise à jour ».

### Task 5: Déclenchement — commande `menu repair` et démarrage de l'IHM

**Files:**
- Modify: `src/main/java/sk/synoAdmin/cli/ConfigCommands.java` (sous-commande `MenuRepairCommand` `@Command(name = "repair")` ajoutée à `Menu`)
- Modify: `src/main/java/sk/synoAdmin/gui/GuiCommand.java` (`execute` et `WindowCommand.execute` : après `Gui.setup()`, `GuiStartup.repairMenus()`)
- Create: `src/main/java/sk/synoAdmin/gui/GuiStartup.java`
- Modify: les 5 `messages*.properties` ; aide picocli (`descriptionKey` / clés `usage` existantes du bundle pour la nouvelle commande et l'option)
- Modify: `README.md`, `README.fr.md` (tableau des commandes : `menu repair`)
- Test: `src/test/java/sk/synoAdmin/cli/ConfigCommandsTest.java` ou `CliTest.java` (aide de `menu repair` disponible et traduite)

**Interfaces:**
- Consumes: `MenuRepair.repair(boolean)`, `MenuRepair.isNeeded()`.
- Produces:
  - CLI `synoRight menu repair [--if-needed]` (option cachée) : `--if-needed` ⇒ `repair(false)`, sinon `repair(true)` ; affiche `cli.menu.repair.done` (+ état `describe(MenuService.status())`), `cli.menu.repair.none` (aucun menu ou rien à faire), `cli.menu.repair.busy` ; code 0 ; erreur ⇒ gestion d'erreur habituelle (code 1).
  - `GuiStartup.repairMenus()` : si `MenuRepair.isNeeded()`, affiche sur l'EDT une petite fenêtre non modale sans bouton « Mise à jour des menus de l'Explorateur… » (`gui.startup.repairingMenus`) pendant `repair(false)` (hors EDT), la ferme, et en cas d'exception affiche `Gui` erreur standard avec `gui.startup.repairFailed` + message, puis rend la main. Le message de redémarrage de l'Explorateur n'est pas affiché ici.

- [ ] **Step 1: Test** — `synoRight menu repair --help` (via `Main.newCommandLine()` comme les tests CLI existants) contient la description anglaise ; `MessagesTest` passe avec les nouvelles clés.
- [ ] **Step 2:** test ⇒ échec. **Step 3:** implémenter. **Step 4:** `mvn -B -q verify`. **Step 5:** commit « Commande menu repair, réparation des menus au démarrage de l'IHM ».

### Task 6: Recherche d'une nouvelle version (logique et réseau)

**Files:**
- Create: `src/main/java/sk/synoAdmin/update/UpdateChecker.java`, `src/main/java/sk/synoAdmin/update/Release.java`
- Modify: `src/main/java/sk/synoAdmin/cli/SynoRightCommand.java` (sous-commande `update`) ou nouveau `src/main/java/sk/synoAdmin/cli/UpdateCommand.java`
- Modify: 5 `messages*.properties`, README (commande `update`)
- Test: `src/test/java/sk/synoAdmin/update/UpdateCheckerTest.java`

**Interfaces:**
- Consumes: `AppVersion`.
- Produces (paquet `sk.synoAdmin.update`) :
  - `record Release(String version, String pageUrl, Optional<String> setupUrl, Optional<String> setupSha256)` ; `static Release parse(String json)` : `tag_name` sans `v` initial, `html_url`, asset dont le nom est `synoRight-<version>-setup.exe`, `digest` de l'asset s'il commence par `sha256:` (hex après le préfixe).
  - `UpdateChecker.newer(Release r, String current): boolean` (`AppVersion.compare > 0`).
  - `UpdateChecker.fetchLatest(): Release` : `HttpClient` (`ProxySelector.getDefault()` après `System.setProperty("java.net.useSystemProxies","true")` si non défini, redirections NORMAL, délai 10 s), en-têtes `Accept: application/vnd.github+json` et `User-Agent: synoRight/<version>` ; statut ≠ 200 ⇒ `UserException(update.check.failed)`.
  - `UpdateChecker.dueForCheck(Optional<Instant> last, Instant now): boolean` (absent ou ≥ 24 h) ; `lastCheck()` / `markChecked(Instant)` dans `HKCU\Software\synoRight\Update`, valeur `LastCheck` (epoch secondes, REG_SZ).
  - `UpdateChecker.installedBySetup(Optional<Path> appDir): boolean` (présence de `unins000.exe`).
  - CLI `synoRight update` : affiche `update.cli.current` et `update.cli.latest` ou `update.cli.upToDate`, avec l'URL de la page ; version de développement ⇒ `update.cli.devVersion`.

- [ ] **Step 1: Tests** — `parse` d'un JSON de release (tag `v0.4`, asset `synoRight-0.4-setup.exe` avec `"digest":"sha256:ab12…"`, asset zip, `html_url`) ⇒ version `0.4`, setupUrl = `browser_download_url` du setup, sha `ab12…` ; JSON sans asset setup ni digest ⇒ `Optional.empty()` ; tag `0.5-beta` ⇒ version `0.5-beta` ; `newer` : `0.4` vs `0.3` vrai, `0.4` vs `0.4` faux, `0.4-beta` vs `0.4` faux ; `dueForCheck` : vide ⇒ vrai, 23 h ⇒ faux, 24 h ⇒ vrai ; `installedBySetup` sur un dossier temporaire avec/sans `unins000.exe`.
- [ ] **Step 2:** tests ⇒ échec. **Step 3:** implémenter (Jackson 3 : `tools.jackson.databind.json.JsonMapper`, comme le reste du projet). **Step 4:** `mvn -B -q verify`. **Step 5:** commit « Recherche d'une nouvelle version sur GitHub (commande update) ».

### Task 7: Nouvelle version dans la fenêtre de configuration

**Files:**
- Modify: `src/main/java/sk/synoAdmin/prefs/Preferences.java` (clé `updateCheck`, défaut `true`, `isUpdateCheck()` / `setUpdateCheck(boolean)`, commentaire d'aide des clés mis à jour)
- Modify: `src/main/java/sk/synoAdmin/gui/ConfigForm.java` (champ `updateCheck`, pris en compte dans `toPreferences` et la détection de modifications)
- Modify: `src/main/java/sk/synoAdmin/gui/ConfigWindow.java` (bandeau, bouton « Rechercher les mises à jour » dans la barre du bas à côté de la version, case « Vérifier les mises à jour au démarrage » dans la section la plus générale existante)
- Create: `src/main/java/sk/synoAdmin/update/Updater.java`
- Modify: 5 `messages*.properties`
- Test: `src/test/java/sk/synoAdmin/update/UpdaterTest.java`, `src/test/java/sk/synoAdmin/gui/ConfigFormTest.java`, test de `Preferences` existant le cas échéant

**Interfaces:**
- Consumes: `UpdateChecker`, `Release`, `Launchers.appDir()`.
- Produces:
  - `Updater.mode(Release r, boolean installedBySetup): Mode` avec `enum Mode { INSTALL, OPEN_PAGE }` (INSTALL seulement si setup installé et `setupUrl` présent).
  - `Updater.download(Release r, Path dir, LongConsumer progress): Path` : télécharge dans `dir` (`%TEMP%` à l'appel), vérifie SHA-256 si fourni (sinon pas de vérification), fichier invalide supprimé + `UserException(update.download.badDigest)`.
  - `Updater.setupCommand(Path setup): List<String>` = `[setup, "/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS", "/RELAUNCH"]` ; `launch(Path setup)` démarre le processus détaché.
  - IHM : à l'ouverture, si `isUpdateCheck()`, version connue et `dueForCheck`, vérification en tâche de fond (`async` existant), `markChecked`, bandeau `update.banner.available` (« synoRight {0} is available. ») avec « Mettre à jour » (`update.banner.install`) et « Notes de version » (`update.banner.notes`, ouvre `pageUrl` via `Desktop.browse`) ; erreur ⇒ rien (debug). Le bouton de la barre du bas vérifie immédiatement et affiche le bandeau ou `update.upToDate` / l'erreur. « Mettre à jour » : mode OPEN_PAGE ⇒ navigateur ; INSTALL ⇒ fermeture comme le bouton Fermer (garde des modifications non enregistrées `CloseGuard`), téléchargement avec barre de progression, `launch`, `System.exit(0)`.

- [ ] **Step 1: Tests** — `mode` : setup installé + url ⇒ INSTALL, sans url ⇒ OPEN_PAGE, portable ⇒ OPEN_PAGE ; `setupCommand` exact ; `download` depuis un `HttpServer` local (`com.sun.net.httpserver`, comme `FakeDsm` s'il l'utilise) : bon digest ⇒ fichier présent, mauvais digest ⇒ exception et fichier absent ; `Preferences` : `updateCheck=false` relu faux, absent ⇒ vrai ; `ConfigForm` : modifier la case marque le formulaire modifié.
- [ ] **Step 2:** tests ⇒ échec. **Step 3:** implémenter. **Step 4:** `mvn -B -q verify` (les tests IHM existants restent verts). **Step 5:** commit « Nouvelle version : bandeau et mise à jour depuis la fenêtre de configuration ».

### Task 8: Installateur Inno Setup, CI et documentation

**Files:**
- Create: `native/installer/synoRight.iss`
- Modify: `.github/workflows/build.yml`
- Modify: `README.md`, `README.fr.md` (Installation : setup recommandé, mise à jour, désinstallation, portable zip ; Construire : installateur ; CI ; corriger au passage, dans la section CI du README.fr, les deux mentions de `native\create-cert.ps1` dont le `\n` est devenu un saut de ligne)

**Interfaces:**
- Consumes: `synoRight.exe menu repair --if-needed`, `synoRight.exe menu uninstall`, `/RELAUNCH` (tâche 7).
- Produces: `synoRight-<version>-setup.exe`.

Script `.iss` (préprocesseur ISPP, paramètres `/DAppVersion=<v> /DSourceDir=<target\dist\synoRight> /O<dir>`) :
- `AppId={{8E4C2A7B-3F1D-4B6E-9A25-C7D0E1F2A3B4}`, `AppName=synoRight`, `AppVersion={#AppVersion}`, `AppPublisher=synoRight`, `AppPublisherURL=https://github.com/skerdudou/synoRight`, `DefaultDirName={autopf}\synoRight`, `DisableDirPage=auto`, `DefaultGroupName=synoRight`, `DisableProgramGroupPage=yes`, `PrivilegesRequired=lowest`, `CloseApplications=yes`, `RestartApplications=no`, `OutputBaseFilename=synoRight-{#AppVersion}-setup`, `UninstallDisplayIcon={app}\synoRightGui.exe`, `ArchitecturesAllowed=x64compatible`, `ArchitecturesInstallIn64BitMode=x64compatible`, `WizardStyle=modern`, `SetupIconFile` : icône de `synoRightGui.exe` si une `.ico` existe dans le dépôt, sinon omis.
- `[Languages]` : english (Default.isl), french, german, spanish, italian.
- `[Files]` : `{#SourceDir}\*` récursif vers `{app}`, `ignoreversion`.
- `[Icons]` : `{autoprograms}\synoRight` ⇒ `{app}\synoRightGui.exe` ; `{autodesktop}\synoRight` avec la tâche `desktopicon` (décochée).
- `[Run]` : `{app}\synoRight.exe` `menu repair --if-needed`, `runhidden waituntilterminated`, statut traduit (`[CustomMessages]` par langue) ; puis `{app}\synoRightGui.exe` `postinstall nowait skipifsilent` (« Lancer synoRight ») et une seconde entrée identique sans `postinstall`/`skipifsilent` avec `Check: WizardSilent and RelaunchRequested` (fonction `[Code]` lisant `/RELAUNCH` dans `ExpandConstant('{param:RELAUNCH|0}')` ou `ParamStr`).
- `[UninstallRun]` : `{app}\synoRight.exe` `menu uninstall`, `runhidden waituntilterminated`, `RunOnceId: "menuUninstall"`.

CI (`build.yml`) :
- job `release` : `if: startsWith(github.ref, 'refs/tags/v') || github.event_name == 'workflow_dispatch'` ; `on:` ajoute `workflow_dispatch:`. Étape Version : sur tag, contrôle actuel ; sinon version lue du pom.
- nouvelle étape après le build : `choco install innosetup --no-progress -y` puis `& "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe" /DAppVersion=$env:VERSION /DSourceDir="$PWD\target\dist\synoRight" /Orelease native\installer\synoRight.iss` (vérifier `$LASTEXITCODE`).
- notes de version : le setup en premier (« Download `synoRight-<v>-setup.exe` and run it: per-user install, no administrator rights; updates install over the previous version, Explorer menus are updated automatically »), zip = version portable, jar inchangé.
- `actions/upload-artifact@v4` de `release/*` (toujours) ; l'étape GitHub release seulement sur tag, `prerelease: ${{ contains(steps.version.outputs.version, '-') }}`, fichiers + setup.

- [ ] **Step 1:** écrire `synoRight.iss` (CRLF ; Inno Setup accepte l'UTF-8 avec BOM pour les messages accentués — utiliser UTF-8 avec BOM).
- [ ] **Step 2:** modifier `build.yml` et les README.
- [ ] **Step 3:** `mvn -B -q verify` (rien de cassé) ; si `ISCC.exe` est disponible localement, compiler le setup ; sinon le signaler — la validation se fera par `gh workflow run build.yml --ref <branche>` après push (fait par le coordinateur).
- [ ] **Step 4:** commit « Installateur Inno Setup par utilisateur, publié par la CI ».
