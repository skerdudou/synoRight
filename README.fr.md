# synoRight

**Gérer les droits de l'espace partagé de Synology Photos depuis l'Explorateur Windows, avec de simples fichiers marqueurs sur le partage du NAS.**

[![Build](https://github.com/skerdudou/synoRight/actions/workflows/build.yml/badge.svg)](https://github.com/skerdudou/synoRight/actions/workflows/build.yml)
[![Dernière version](https://img.shields.io/github/v/release/skerdudou/synoRight?include_prereleases&sort=semver)](https://github.com/skerdudou/synoRight/releases)
![Java 25](https://img.shields.io/badge/Java-25-orange)
![Windows](https://img.shields.io/badge/platform-Windows-0078D6)

[English](README.md)

synoRight synchronise les droits d'accès des dossiers de l'**espace partagé de Synology Photos** (API `SYNO.FotoTeam`) avec des fichiers marqueurs cachés `@<nom court>.synoRight` posés dans les dossiers photos du partage réseau du NAS. Les droits deviennent visibles et modifiables là où sont les photos : dans l'Explorateur, d'un clic droit.

## Principe

- Chaque groupe du NAS géré reçoit un **nom court** (ex : `Smith` pour le groupe « Smith family »). Seul l'id du groupe (gid) le relie au NAS ; le nom court sert au nom des fichiers marqueurs et aux entrées du menu.
- Un dossier photos contient un fichier caché `@<nom court>.synoRight` pour chaque groupe autorisé à le voir. Exemple : `\\NAS\photo\2024\Vacances\@Smith.synoRight`.
- synoRight compare ces fichiers avec les droits réels de Synology Photos et applique le delta, dans un sens ou dans l'autre :
  - **Fichiers → Photos** (`push`) : les fichiers font foi, les droits de Synology Photos sont mis à jour. Les droits hérités du dossier parent par les nouveaux dossiers dans Synology Photos sont aussi retirés.
  - **Photos → fichiers** (`pull`) : Synology Photos fait foi, les fichiers marqueurs sont créés ou supprimés.
- Les droits sont gérés sur **2 niveaux de dossiers** sous la racine photo (ex : `2024` et `2024\Vacances`), les niveaux sur lesquels Synology Photos permet de les gérer. Donner à un groupe l'accès à un sous-dossier lui donne aussi l'accès au dossier parent.
- Les dossiers pas encore indexés par Synology Photos sont signalés et ignorés.

## Fonctionnalités

- **Fenêtre de configuration** (`synoRightGui.exe`) : connexion au NAS (OTP et certificat auto-signé pris en charge), dossier photos, groupes gérés et leurs noms courts, menu de l'Explorateur, synchronisation (aperçu des écarts, puis Fichiers → Photos ou Photos → fichiers), langue.
- **Menu contextuel de l'Explorateur** sur les dossiers et le fond des dossiers :
  - **menu Windows 11**, au premier niveau du clic droit (extension native dans un package sparse signé) ;
  - **menu classique** (sous « Afficher plus d'options »), sans droits administrateur ;
  - entrées au choix, séparément pour chaque menu : droits du dossier, ouvrir dans Synology Photos, état de la synchro, les deux synchros complètes, ajout / retrait de chaque groupe, configuration de synoRight.
- **Fenêtre des droits du dossier** : les groupes autorisés sur un dossier, à cocher ou décocher.
- **Ligne de commande** (`synoRight.exe`) pour toutes les opérations, avec simulation et confirmation.
- Mot de passe et session chiffrés avec une clé propre au poste ; la session Synology (OTP) est mémorisée.
- Disponible en **anglais, français, allemand, espagnol et italien**.

## Installation

### Installateur (recommandé)

1. Télécharger `synoRight-<version>-setup.exe` depuis la page [Releases](https://github.com/skerdudou/synoRight/releases) et le lancer. Il embarque son propre runtime Java : rien d'autre à installer.
2. L'installation se fait **par utilisateur** : aucun droit administrateur n'est nécessaire. Elle ajoute un raccourci dans le menu Démarrer (et, en option, sur le Bureau) et s'installe dans `%LOCALAPPDATA%\Programs\synoRight`.
3. Lancer **synoRight** : la fenêtre de configuration s'ouvre. Renseigner la connexion, la tester, choisir le dossier photos et les groupes, puis installer le menu de l'Explorateur.

Le **menu Windows 11** s'installe pour l'utilisateur courant, mais la première installation sur un poste demande **une seule fois une autorisation administrateur** : Windows doit approuver le certificat `CN=synoRight` qui signe le package du menu. L'invite UAC affiche « Windows PowerShell » : elle importe `synoRight.cer` (du dossier de l'application) dans le magasin « Personnes autorisées » (TrustedPeople) de l'ordinateur. Le menu classique ne demande pas de droits administrateur.

**Mise à jour** : la fenêtre de configuration signale qu'une nouvelle version est disponible et peut la télécharger et l'installer. On peut aussi lancer le nouveau setup par-dessus l'ancienne version. Les menus de l'Explorateur sont réparés automatiquement.

**Désinstallation** : Paramètres Windows > Applications > synoRight. Le désinstallateur retire les menus de l'Explorateur ; la configuration est conservée.

### Zip portable

`synoRight-<version>-windows-x64.zip` contient la même application : le décompresser dans par exemple `C:\Program Files` (l'archive contient un dossier `synoRight` ; écrire à cet endroit demande les droits administrateur) ou dans n'importe quel dossier, puis lancer **`synoRightGui.exe`**. Le dossier `synoRight` contient `synoRight.exe` (ligne de commande), `synoRightGui.exe` (sans console) et, pour le menu Windows 11, les fichiers `synoRightMenu-<version>.dll`, `synoRight.msix` et `synoRight.cer`.

Les commandes du menu pointent vers l'application à son emplacement courant : après le remplacement ou le déplacement du dossier, les menus sont réparés automatiquement au prochain démarrage de l'IHM (ou par `synoRight menu repair`).

### Jar

Un jar seul (`synoRight-<version>.jar`) est aussi publié ; il se lance par `java -jar synoRight-<version>.jar <commande>` (Java 25+) mais n'inclut pas le menu Windows 11.

## Utilisation

### Fenêtre de configuration

`synoRightGui.exe` (ou `synoRight gui`) ouvre la fenêtre de configuration, avec ces sections et, dans la barre du bas, la liste déroulante de la **langue** (automatique, c'est-à-dire la langue d'affichage de Windows, ou une langue fixe) :

- **Connexion** : URL de DSM, compte, mot de passe (mémorisé au choix), test de connexion (demande l'OTP si besoin, propose de faire confiance à un certificat auto-signé).
- **Dossier photos** : la racine des photos sur le partage (ex : `\\NAS\photo`).
- **Groupes** : les groupes du NAS à gérer, choisis dans la liste des groupes du NAS, et leurs noms courts.
- **Menu Explorateur** : entrées de chaque menu, menu à installer (Windows 11, classique ou les deux), installation / mise à jour / désinstallation.
- **Synchronisation** : analyse des écarts, puis Fichiers → Photos ou Photos → fichiers après confirmation.

### Menu de l'Explorateur

Clic droit sur un dossier photos (ou sur le fond d'un dossier ouvert), menu « Droits Synology Photos ». Les entrées ouvrent des fenêtres, sans console :

- **Droits du dossier…** : les groupes autorisés sur le dossier, à cocher ou décocher ;
- **État de la synchronisation…** et **Synchroniser fichiers → Photos… / Photos → fichiers…** : aperçu des écarts, puis synchronisation après confirmation ;
- **Ajouter / Retirer &lt;groupe&gt;** : une entrée par groupe géré ;
- **Ouvrir dans Synology Photos** (désactivée par défaut) et **Configuration de synoRight…**.

**Plusieurs dossiers** peuvent être sélectionnés à la fois. **Droits du dossier…** affiche alors un tableau avec une ligne par dossier et une case par groupe : un clic sur l'en-tête d'un groupe coche ou décoche toute la colonne, cocher un groupe sur un sous-dossier le coche aussi sur son dossier parent quand celui-ci est dans le tableau, et les cases où Synology Photos et le fichier marqueur divergent sont mises en évidence (info-bulle avec les deux états). **Ajouter / Retirer &lt;groupe&gt;** et **Ouvrir dans Synology Photos** s'appliquent à chaque dossier sélectionné ; un dossier en erreur (pas encore indexé, par exemple) n'empêche pas le traitement des autres.

Remarques :

- Sélection multiple avec le **menu classique** : l'Explorateur lance un processus par dossier sélectionné (15 dossiers au plus) ; le premier regroupe les dossiers des autres, ce qui ajoute environ 1,5 s avant l'ouverture de la fenêtre. Le menu Windows 11 reçoit directement toute la sélection.
- **Droits du dossier…** attend les dossiers pas encore indexés par Synology Photos (tout juste copiés, par exemple) : la fenêtre affiche « En attente de l'indexation des dossiers par Synology Photos… » et réessaie toutes les 10 secondes, 10 minutes au plus (une autre erreur du NAS n'est réessayée que deux fois, pour qu'une erreur durable comme un accès refusé s'affiche vite). Fermer la fenêtre annule l'attente. Les dossiers toujours inconnus à la fin sont signalés, les autres affichés.

- Sans choix explicite, le menu Windows 11 est utilisé s'il est disponible, sinon le menu classique (`menuTarget`).
- Sous Windows 11, l'entrée du menu Windows 11 apparaît aussi dans « Afficher plus d'options » (comportement de Windows pour les extensions de package). Avec « Les deux », elle s'y masque pour laisser la place au menu classique.
- Le menu Windows 11 n'affiche pas de séparateurs entre les blocs d'entrées (limite de Windows 11 pour les sous-menus des extensions) ; le menu classique, si.
- Les changements de groupes ou d'entrées sont pris en compte **sans réinstaller** le menu (`groups add/remove` et la fenêtre de configuration le mettent à jour).
- Si le menu Windows 11 n'apparaît pas ou n'est pas à jour après une installation ou une mise à jour, redémarrer l'Explorateur (cache de Windows).
- Un menu installé par une ancienne version (syntaxe `-a ACTION`) affiche un message invitant à le réinstaller.

### Ligne de commande

```
synoRight [-c <dossierConfig>] [-v] <commande>
```

| Commande | Description |
|---|---|
| `init` | Assistant de configuration en console : teste la connexion tout de suite (OTP compris), vérifie le dossier photos, fait choisir les groupes parmi ceux du NAS, propose d'installer le menu |
| `status` | Écarts entre fichiers de droits et Synology Photos, sans rien modifier |
| `pull [-n] [-y]` | Synology Photos → fichiers : crée / supprime les fichiers `.synoRight` |
| `push [-n] [-y]` | Fichiers → Synology Photos : applique les droits décrits par les fichiers |
| `show [dossier...]` | Droits de dossiers côté Photos et côté fichiers |
| `grant <groupe> [dossier...]` | Donne le droit à un groupe sur des dossiers (et leur parent) |
| `revoke <groupe> [dossier...]` | Retire le droit d'un groupe sur des dossiers |
| `rights [dossier...]` | Choix des groupes autorisés sur chaque dossier (invite en console), puis application de l'ensemble |
| `config` | Affiche la configuration et vérifie dossier, connexion et groupes |
| `groups` / `groups add` / `groups remove` | Liste / ajoute (parmi les groupes du NAS) / retire des groupes gérés |
| `password [--forget]` | Change (après vérification auprès du NAS) ou oublie le mot de passe mémorisé |
| `logout` | Oublie la session Synology mémorisée (la prochaine connexion redemande l'OTP) |
| `menu install [--modern\|--classic\|--all]` / `menu uninstall` / `menu repair` | Menu contextuel de l'Explorateur Windows |
| `update` | Recherche une version plus récente sur GitHub |
| `gui` | Ouvre la fenêtre de configuration (c'est ce que lance `synoRightGui.exe`) |

- `<groupe>` est le nom court d'un groupe géré ; `[dossier]` vaut par défaut le répertoire courant.
- `pull` et `push` affichent le détail des modifications puis demandent confirmation : `-y` / `--yes` pour appliquer directement, `-n` / `--dry-run` pour seulement simuler.
- Options communes : `-c` / `--config` répertoire de configuration, `-v` / `--verbose` temps de traitement et détail des erreurs, `-h` / `--help` aide (aussi par commande : `synoRight pull -h`), `-V` / `--version` version.

Relancer `init` sur une configuration existante propose les valeurs actuelles, mot de passe compris (Entrée pour les conserver). La session mémorisée n'est remplacée qu'à l'enregistrement : abandonner l'assistant ne change rien.

### Dossiers créés par un autre outil

Un outil de copie ou d'import peut ouvrir la fenêtre des droits sur les dossiers qu'il vient de créer :

```
synoRightGui.exe -c <dossierConfig> rights --new-folders [--wait-index <minutes>] <dossier>...
```

- Tous les dossiers créés peuvent être passés, niveaux mélangés : seuls les dossiers de niveau 1 et 2 sous le dossier photos sont gardés, les plus profonds sont ignorés sans message (ils suivent leur parent), les doublons retirés. Plus rien : pas de fenêtre, code de sortie 0. Si aucun dossier n'est sous le dossier photos (chemin écrit autrement que dans la configuration, lecteur réseau non reconnu), une erreur les liste.
- Les dossiers pas encore indexés par Synology Photos sont attendus, comme depuis le menu de l'Explorateur ; `--wait-index <minutes>` change la limite de 10 minutes (0 = pas d'attente).
- Les dossiers peuvent être donnés en chemin UNC (`\\NAS\photo\...`) ou sur un lecteur réseau (`Z:\...`, connexion mémorisée).

## Configuration

Par défaut dans `~/.synoRight/` (à défaut, une configuration présente dans le répertoire courant est utilisée ; `-c` pour un autre répertoire). Le fichier `config.synoRight.properties` est éditable à la main (UTF-8, accents acceptés) :

```properties
folder=\\\\NAS\\photo
url=https://nas.example.com:5001
user=admin
# mot de passe chiffré (écrit par la fenêtre de configuration, init ou password), optionnel : demandé à la connexion sinon
password=v2:...
# id:nom court;id:nom court : id (gid) du groupe sur le NAS et nom court, libre (fichiers @<nom court>.synoRight, entrées du menu)
groups=65536:Smith;65537:Jones
# temps de traitement et détail des erreurs
verbose=false
# optionnel : empreinte SHA-256 du certificat auto-signé du NAS à accepter (proposé au test de connexion)
certFingerprint=AB:CD:...
# optionnel : entrées de chaque menu de l'Explorateur (rights, open, status, push, pull, groups, config) ; par défaut : rights,status,push,config
menuEntries.modern=rights,status,push,config
menuEntries.classic=rights,status,push,pull,groups,config
# optionnel : modern, classic ou all (absent = Windows 11 si disponible, sinon classique)
menuTarget=modern
# auto (langue d'affichage de Windows), en, fr, de, es ou it
language=auto
```

- L'ancienne clé `menuEntries` (commune aux deux menus) reste lue en l'absence de `menuEntries.modern` / `menuEntries.classic`.
- Changer le nom court d'un groupe déjà géré change le nom des fichiers attendus : les anciens `@<nom>.synoRight` ne sont plus pris en compte, « Photos → fichiers » recrée les nouveaux.
- Les commandes qui modifient la configuration (`init`, `groups`, `password`, la fenêtre de configuration, les migrations automatiques) réécrivent le fichier : les commentaires ajoutés à la main ne sont pas conservés.
- **Fichier de session** : la session Synology (`cookieSession`, `synoToken`, `cookieDid`) est mémorisée à part, dans `session.synoRight.properties` du même répertoire : la configuration peut être copiée sur un autre poste sans y embarquer de session. Les anciennes configurations qui la contenaient sont migrées automatiquement.
- **Fichier de clé** : le mot de passe et la session sont chiffrés en AES-GCM avec une clé aléatoire propre au poste, stockée dans `~/.synoRight/key` (accès restreint au propriétaire). Si la clé est absente ou perdue, le mot de passe est simplement redemandé à la connexion (et la session recréée) ; pour utiliser la même configuration sur un autre poste, copier aussi le fichier de clé.
- Si le mot de passe mémorisé est refusé par le NAS, il est redemandé et peut être mémorisé à la place.
- **Certificat auto-signé** : si DSM présente un certificat non reconnu (certificat par défaut, auto-signé), son empreinte SHA-256 est affichée et il est proposé de lui faire confiance. Seul ce certificat précis est alors accepté, en plus de la validation standard ; s'il change (renouvellement), la connexion échoue à nouveau jusqu'à ce qu'il soit de nouveau approuvé.

## Langues

synoRight est traduit en **anglais** (par défaut), **français**, **allemand**, **espagnol** et **italien** : fenêtres, dialogues, entrées des menus de l'Explorateur, messages de la console et aide de la ligne de commande.

La clé `language` (`auto`, `en`, `fr`, `de`, `es`, `it`) choisit la langue ; `auto` prend la langue d'affichage de Windows si c'est l'une de celles-ci, l'anglais sinon. Elle se change dans la fenêtre de configuration ; la nouvelle langue s'applique à la prochaine fenêtre ouverte et aux menus de l'Explorateur, réécrits à l'enregistrement.

## Construire depuis les sources

Prérequis : **JDK 25** et **Maven**.

```
mvn package
```

produit `target/synoRight.jar` (lancé par `java -jar target/synoRight.jar <commande>`).

```
mvn package -Pexe
```

produit l'application autonome (runtime Java embarqué, construite par `jpackage`) dans `target/dist/synoRight/`, menu Windows 11 compris. Il faut en plus :

- **Visual Studio 2022 Build Tools** avec la charge de travail C++, et le **Windows 11 SDK** (MSBuild est trouvé par `vswhere`, `makeappx` et `signtool` dans le dossier Windows Kits) ;
- un **certificat de signature** du package du menu (sujet `CN=synoRight`, identique au manifeste du package) :
  - créé une seule fois par `native\create-cert.ps1` (dans `~\.synoRight-sign\`, mot de passe protégé par DPAPI pour l'utilisateur courant), ou
  - fourni par les variables d'environnement `SYNORIGHT_SIGN_PFX` (chemin du `.pfx`) et `SYNORIGHT_SIGN_PASSWORD`.

`mvn package -Pexe -Dnative.skip` construit l'application sans le menu Windows 11 (aucun prérequis natif) ; le menu classique reste disponible.

### Construire l'installateur

Le setup est construit à partir du dossier de l'application avec [Inno Setup 6](https://jrsoftware.org/isinfo.php) (après `mvn package -Pexe`), dans PowerShell depuis la racine du dépôt (des chemins relatifs seraient résolus depuis le dossier du script) :

```
ISCC.exe /DAppVersion=<version> /DSourceDir="$PWD\target\dist\synoRight" /O"$PWD\target" native\installer\synoRight.iss
```

Il produit `target\synoRight-<version>-setup.exe`.

## Versions publiées et CI

Le workflow GitHub Actions [`build.yml`](.github/workflows/build.yml) lance les tests (`mvn verify`) à chaque push et pull request. Pousser un tag `v*` construit l'application complète avec le menu Windows 11 et crée une version en brouillon (une pré-version quand la version contient un `-`) avec `synoRight-<version>-setup.exe`, `synoRight-<version>-windows-x64.zip` et `synoRight-<version>.jar`. Les pull requests et les lancements manuels (`workflow_dispatch`) construisent aussi l'application, l'installateur, le zip et le jar et les joignent comme artefacts du workflow, sans créer de version. Le package du menu est signé avec le certificat fourni par les secrets `SYNORIGHT_SIGN_PFX_BASE64` et `SYNORIGHT_SIGN_PASSWORD`, sinon avec un certificat généré pour cette seule construction (les notes de version le signalent alors).

Pour créer les secrets à partir d'un .pfx (par exemple synoRight.pfx produit par `native\create-cert.ps1`) :

- `SYNORIGHT_SIGN_PFX_BASE64` : le résultat, en PowerShell, de `[Convert]::ToBase64String([IO.File]::ReadAllBytes('synoRight.pfx'))`.
- `SYNORIGHT_SIGN_PASSWORD` : le mot de passe du .pfx. Pour un .pfx produit par `native\create-cert.ps1`, il est stocké protégé par DPAPI dans synoRight.pfx.pwd ; le lire, avec le même utilisateur Windows, par `[System.Net.NetworkCredential]::new('', (Get-Content synoRight.pfx.pwd | ConvertTo-SecureString)).Password`.
