# IHM de configuration, droits par dossier et menu Windows 11 natif — design

Date : 2026-10-16 — statut : validé en conversation, à relire.

## Objectif

Faire de synoRight un outil utilisable sans terminal :

- double-clic sur l'application → IHM de configuration (connexion NAS, dossier photos, groupes, menu Explorateur, synchronisation) ;
- menu « Droits Synology Photos » directement dans le **nouveau menu contextuel de Windows 11** (premier niveau, sous-menu dépliant), avec choix des entrées affichées ;
- action « Droits du dossier… » : choisir les groupes autorisés sur un dossier dans une fenêtre ;
- synchronisation fichiers → Photos (`push`) confortable, avec aperçu : c'est elle qui retire les droits que Synology Photos fait hériter du parent aux nouveaux dossiers.

### Ce que l'utilisateur a demandé / validé

- IHM au lancement de l'exe, installation du menu, configuration des groupes.
- Menu Windows 11 natif **dans ce lot** (pas en seconde phase), avec choix des entrées.
- Fenêtre IHM (et non console seule) pour configurer les droits d'un dossier.
- Les droits « par défaut » sont l'héritage du parent sur les groupes gérés : `push` suffit, il faut le rendre accessible.
- Pas de tâche planifiée.

### Hors périmètre

- Synchro automatique (tâche planifiée Windows ou cron côté NAS : idée gardée pour plus tard).
- Profondeur au-delà de 2 niveaux (limite de Synology Photos, inchangée).
- Installeur MSI/EXE : la distribution reste le dossier `target/dist/synoRight/` à copier.

## Architecture

```
synoRight.exe     (console)   → CLI picocli, inchangée (+ commande 'rights')
synoRightGui.exe  (sans console) → IHM Swing : fenêtre de config, fenêtres lancées par le menu
synoRightMenu.dll (C++)       → menu Windows 11 (IExplorerCommand), chargée par Explorer
synoRight.msix    (sparse)    → identité de package déclarant la DLL à Explorer
```

Un seul jar Java pour les deux lanceurs ; la DLL ne contient aucune logique métier : elle lance `synoRightGui.exe` avec des arguments.

### 1. Lanceurs

- `synoRight.exe` : lanceur jpackage actuel (`--win-console`), comportement inchangé.
- `synoRightGui.exe` : lanceur jpackage additionnel (`--add-launcher`, sans console) qui exécute `sk.synoAdmin.Main gui ...`.
- Sous-commande `gui` (masquée de l'aide principale) :
  - `gui` → fenêtre de configuration ;
  - `gui rights <dossier>` → fenêtre « Droits du dossier » ;
  - `gui sync [--push|--pull] [<dossier>]` → fenêtre de synchronisation (analyse lancée à l'ouverture) ;
  - `gui grant|revoke <groupe> <dossier>` → action rapide, résultat dans un petit dialogue.
- Les options globales `-c` / `-v` restent valables. Avec le jar seul : `java -jar synoRight.jar gui`.
- Erreurs en mode IHM : `UserException` → dialogue d'erreur avec le message ; autre exception → message + détail repliable (stack trace).

### 2. Interaction utilisateur (refacto)

Interface `sk.synoAdmin.ui.Interaction` :

```java
String promptLine(String label, String defaultValue);
String promptSecret(String label);            // null si annulé
boolean promptBool(String label);
List<Integer> promptChoices(String label, List<String> items);
void info(String message);
```

- `ConsoleInteraction` : code actuel de `ConsoleUtil` (déplacé), aucun changement de comportement.
- `SwingInteraction` : `JOptionPane` / dialogues exécutés sur l'EDT (`invokeAndWait` si appelé d'un autre thread). Annulation d'un mot de passe ou OTP → `UserException("Connexion annulée.")`.
- `ConsoleUtil` garde ses méthodes statiques et délègue à l'`Interaction` courante (`ConsoleUtil.setInteraction(...)`), console par défaut : `SynoApiClient` (mot de passe, OTP, mémorisation) fonctionne en IHM sans modification de sa logique.
- `pauseBeforeExit` reste spécifique console.

### 3. Moteur : résultats structurés (refacto)

`RightEngine` sépare calcul et affichage ; la CLI produit exactement les mêmes sorties qu'aujourd'hui (tests existants inchangés).

- `SyncPlan planPush()` / `SyncPlan planPull()` : deltas par groupe (dossiers à ajouter / retirer), dossiers non indexés et dossiers inconnus du partage (aujourd'hui seulement imprimés en avertissement).
- `void apply(SyncPlan)` : applique un plan (fichiers ou Photos selon son sens).
- `FolderRights folderRights(String dossier)` : pour chaque groupe géré, droit côté Photos et côté fichier (remplace le calcul de `showFolder`).
- `void setFolderRights(String dossier, Set<Group> cible)` : applique le delta par `addRightToFolder` / `removeRightToFolder` (règle existante : un ajout sur un dossier de niveau 2 donne aussi le droit au parent).
- Les méthodes d'affichage (`status`, `dbToFs`, `fsToDb`, `showFolder`) deviennent des fonctions de la CLI qui formatent ces résultats.

### 4. Fenêtre de configuration

Swing + FlatLaf (thème clair/sombre suivant Windows). Une fenêtre, navigation latérale par sections :

1. **Connexion** — URL, compte, mot de passe, case « Mémoriser le mot de passe », bouton *Tester la connexion*. OTP et certificat auto-signé (sujet, émetteur, validité, empreinte SHA-256, comme `init`) via dialogues. Au premier lancement (pas de configuration), les autres sections sont désactivées tant que la connexion n'a pas réussi.
2. **Dossier photos** — champ + *Parcourir…*, vérification d'accès immédiate.
3. **Groupes** — groupes du NAS en cases à cocher ; groupes configurés absents ou renommés signalés (logique de `ConfigCommands.checkGroups`).
4. **Menu Explorateur** — cases des entrées (voir §6), choix de la cible : *Windows 11 (menu principal)*, *Menu classique (Afficher plus d'options)*, ou les deux ; état de chaque menu ; boutons *Installer / Mettre à jour* et *Désinstaller*. L'option Windows 11 est désactivée (avec explication) si les fichiers natifs sont absents ou hors Windows 11.
5. **Synchronisation** — *Analyser* : tableau des écarts (dossier, groupe, Photos, fichiers) + avertissements (non indexés, absents du partage) ; boutons *Fichiers → Photos* (push) et *Photos → fichiers* (pull), confirmation récapitulant le nombre de modifications.

Bouton *Enregistrer* : écrit la configuration (comme `init` : la session de travail ne remplace la session mémorisée qu'à l'enregistrement) puis met à jour le menu installé. Fermer avec des modifications non enregistrées → confirmation. Pied de fenêtre : version, lien « Ouvrir le dossier de configuration ».

Les appels réseau/disque se font hors EDT (`SwingWorker`), avec indicateur d'activité et boutons désactivés pendant l'opération.

### 5. Fenêtre « Droits du dossier »

- Titre : chemin relatif du dossier (ex. `/2024/Vacances`).
- Une ligne par groupe géré : case à cocher (état initial = droit côté Photos), colonnes *Photos* / *Fichier*, écart signalé en couleur.
- Note : « Ajouter un groupe sur un sous-dossier le donne aussi au dossier parent. »
- *Appliquer* : applique le delta via `setFolderRights`, puis recharge l'état. *Fermer*.
- Dossier hors du dossier photos, trop profond ou non indexé → dialogue d'erreur (messages de `checkFolder` / `resolveFolder`).
- Équivalent console : `synoRight rights [<dossier>]` (choix par `promptChoices`, cases pré-remplies avec l'état actuel).

### 6. Entrées du menu Explorateur

Clé de configuration `menuEntries` (liste séparée par des virgules ; absente → valeur par défaut). Mêmes entrées pour les deux menus :

| Id | Libellé | Action | Défaut |
|---|---|---|---|
| `rights` | Droits du dossier… | `gui rights "%V"` | oui |
| `status` | État de la synchronisation… | `gui sync` | oui |
| `push` | Synchroniser fichiers → Photos… | `gui sync --push` | oui |
| `pull` | Synchroniser Photos → fichiers… | `gui sync --pull` | non |
| `groups` | Ajouter / Retirer <groupe> (pour chaque groupe) | `gui grant|revoke <groupe> "%V"` | non |
| `open` | Ouvrir dans Synology Photos | ouvre l'URL du dossier dans le navigateur | non |
| `config` | Configuration de synoRight… | `gui` | oui |

Séparateurs entre les blocs (droits / synchro / groupes / config). Toutes les entrées lancent `synoRightGui.exe` : aucune console ne s'ouvre depuis l'Explorateur. L'entrée `open` construit l'URL Synology Photos du dossier à partir de son id (format à vérifier sur le DSM réel pendant l'implémentation ; entrée retirée si l'URL ne peut pas être construite de façon fiable).

L'entrée racine n'apparaît que pour les dossiers sous le dossier photos (classique : `AppliesTo` comme aujourd'hui ; Windows 11 : `GetState` de la DLL) et sur le fond d'un dossier ouvert.

### 7. Menu classique

`ContextMenuInstaller` existant, adapté : entrées filtrées par `menuEntries`, commandes vers `synoRightGui.exe` (ou `javaw -jar` hors exe), `--pause` n'est plus nécessaire. Le message d'invitation à réinstaller pour l'ancienne syntaxe reste.

### 8. Menu Windows 11 natif

#### DLL `synoRightMenu.dll` (`native/`)

- C++17, WRL, projet MSBuild (`native/synoRightMenu.vcxproj`), x64.
- Classe COM racine (CLSID fixe) implémentant `IExplorerCommand` + `IObjectWithSite` : titre « Droits Synology Photos », icône = `synoRightGui.exe,0`, `ECF_HASSUBCOMMANDS`.
- `EnumSubCommands` : sous-commandes construites à chaque ouverture depuis `HKCU\Software\synoRight\Menu` :
  - `Launcher` (REG_SZ) : chemin de `synoRightGui.exe` ;
  - `ConfigDir` (REG_SZ) ;
  - `PhotoFolder` (REG_SZ) ;
  - sous-clé `Entries\NN` : `Title`, `Args` (gabarit avec `%V` remplacé par le dossier), `SeparatorBefore` (DWORD).
  Modifier les groupes ou les entrées ne demande donc qu'une réécriture du registre, pas de réinstallation du package.
- `GetState` : `ECS_HIDDEN` si aucun élément sélectionné n'est un dossier sous `PhotoFolder` (comparaison insensible à la casse, chemins UNC et lecteurs mappés tels que configurés) ou si la clé de registre est absente.
- `Invoke` : `CreateProcessW(Launcher, "-c <ConfigDir> " + Args)` ; un seul dossier pris en compte (le premier de la sélection).
- Aucune allocation ou I/O lourde dans `GetState`/`GetTitle` (Explorer les appelle à chaque clic droit).

#### Package sparse `synoRight.msix`

- `AppxManifest.xml` : `Identity Name="synoRight" Publisher="CN=synoRight"`, `uap10:AllowExternalContent`, `Application` pointant vers `synoRightGui.exe` (`uap10:TrustLevel="mediumIL"`, `RuntimeBehavior="win32App"`, pas de tuile), extensions `desktop4:FileExplorerContextMenus` pour `Directory` et `Directory\Background`, `com:SurrogateServer` déclarant la DLL.
- Ressources minimales (logo PNG requis par le manifest).

#### Build (profil Maven `exe`)

1. `msbuild native/synoRightMenu.vcxproj /p:Configuration=Release /p:Platform=x64` (localisé par `vswhere`) ;
2. copie de la DLL dans l'app-image jpackage ;
3. `makeappx pack` du manifest → `synoRight.msix` ;
4. `signtool sign` avec le certificat de signature (`SYNORIGHT_SIGN_PFX` / `SYNORIGHT_SIGN_PASSWORD`, variables d'environnement) ; export du certificat public `synoRight.cer` dans l'app-image.

Si le certificat n'est pas fourni, le build s'arrête avec un message expliquant `native/create-cert.ps1`. Un profil sans natif reste possible (`mvn package -Pexe -Dnative.skip`) : l'IHM désactive alors l'option Windows 11.

`native/create-cert.ps1` : `New-SelfSignedCertificate -Type CodeSigningCert -Subject "CN=synoRight"` dans le magasin utilisateur, export `.pfx` protégé par mot de passe hors du dépôt (`*.pfx` ignoré par git).

#### Installation sur un poste (bouton de l'IHM, ou `synoRight menu install --modern`)

1. Si `synoRight.cer` n'est pas approuvé : PowerShell élevé (`Start-Process -Verb RunAs`) → `Import-Certificate -CertStoreLocation Cert:\LocalMachine\TrustedPeople`. UAC une fois par poste ; refus → message, menu classique toujours possible.
2. `Add-AppxPackage -Path synoRight.msix -ExternalLocation <dossier de l'application>` (utilisateur courant, sans droits admin).
3. Écriture des clés `HKCU\Software\synoRight\Menu`.

Désinstallation : `Remove-AppxPackage` + suppression des clés. Déplacement de l'application → réinstaller (comme aujourd'hui). Mise à jour de l'application avec un package de version supérieure → `Add-AppxPackage` remplace l'ancien.

## Commandes CLI

- Nouvelles : `rights [<dossier>]`, `gui ...` (masquée), `menu install [--modern|--classic|--all]` (défaut : selon la configuration `menuTarget`).
- Configuration : nouvelles clés `menuEntries` et `menuTarget` (`modern`, `classic`, `all` ; défaut `modern` sous Windows 11 si les fichiers natifs sont présents, sinon `classic`).
- `init` reste disponible (usage terminal) et propose en fin d'assistant d'ouvrir l'IHM pour le menu.

## Gestion d'erreurs

- Mêmes `UserException` que la CLI, affichées en dialogue.
- NAS injoignable / dossier inaccessible pendant l'ouverture d'une fenêtre lancée par le menu → dialogue d'erreur puis fermeture.
- Échec d'installation du menu Windows 11 (certificat refusé, `Add-AppxPackage` en erreur) → message détaillé (sortie PowerShell) et proposition d'installer le menu classique.
- La DLL ne lève jamais d'exception vers Explorer : toute erreur → entrée masquée.

## Tests

- **Unitaires (JUnit, faux DSM existant)** : `SyncPlan` push/pull, `apply`, `FolderRights`, `setFolderRights` (y compris propagation au parent), non-régression des sorties CLI, `ConsoleInteraction` (entrée simulée), génération du `.reg` classique filtré par `menuEntries`, valeurs de registre du menu Windows 11 (génération testée sans écrire dans le registre), lecture/écriture de `menuEntries`/`menuTarget`.
- **IHM** : modèles (tableaux, état de formulaire) testés sans affichage (`java.awt.headless`) ; check-list manuelle pour les fenêtres.
- **Natif** : check-list manuelle — menu visible au premier niveau sur un dossier photo et sur le fond, absent ailleurs, sous-menu conforme à la configuration, prise en compte d'un changement de groupe sans réinstaller, lancement des fenêtres, désinstallation propre.

## Prérequis de build

- JDK 25 (jpackage), Maven.
- Visual Studio 2022 Build Tools (charge C++), Windows 11 SDK (makeappx, signtool).
- Certificat de signature créé par `native/create-cert.ps1`.

## Documentation

README mis à jour : lanceurs, IHM, menu Windows 11 (installation, UAC unique, certificat), nouvelles commandes et clés de configuration, prérequis de build.
