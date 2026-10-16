# synoRight

Outil en ligne de commande pour synchroniser les droits d'accès des albums **Synology Photos** (espace partagé, API `SYNO.FotoTeam`) avec des fichiers marqueurs posés sur le partage réseau du NAS.

Principe : un dossier photo porte un fichier caché `@<groupe>.synoRight` pour chaque groupe autorisé. L'outil compare ces fichiers avec les permissions réelles de Synology Photos et applique le delta, dans un sens ou dans l'autre. Il gère 2 niveaux de dossiers sous la racine photo.

## Installation

1. Construire l'application autonome (runtime Java embarqué, rien à installer sur le poste) :
   ```
   mvn package -Pexe
   ```
   Prérequis : JDK 25, Maven, et pour le menu Windows 11 : Visual Studio 2022 Build Tools (charge de travail C++) et Windows 11 SDK, ainsi qu'un certificat de signature créé une seule fois par `native\create-cert.ps1`. `-Dnative.skip` construit l'application sans le menu Windows 11 (aucun de ces prérequis natifs n'est alors nécessaire).

   Puis copier le dossier `target/dist/synoRight/` à son emplacement définitif (ex : `C:\Program Files\synoRight`). Il contient `synoRight.exe` (console), `synoRightGui.exe` (sans console) et, pour le menu Windows 11, `synoRightMenu.dll`, `synoRight.msix` et `synoRight.cer`.
2. Lancer **`synoRightGui.exe`** : la fenêtre de configuration s'ouvre (voir « Interface graphique »). Ou, en console, l'assistant :
   ```
   synoRight init
   ```
   Il demande l'URL de DSM et le compte, **teste la connexion tout de suite** (OTP compris), puis le dossier photos (vérifié), fait choisir les groupes **dans la liste des groupes du NAS**, et propose d'installer le menu contextuel de l'Explorateur.

Relancer `init` sur une configuration existante propose les valeurs actuelles, mot de passe compris (Entrée pour les conserver). La session mémorisée n'est remplacée qu'à l'enregistrement : abandonner l'assistant ne change rien.

Sans le profil `exe`, `mvn package` produit `target/synoRight.jar`, utilisable avec `java -jar synoRight.jar <commande>` (Java 25+).

## Interface graphique

`synoRightGui.exe` (ou `synoRight gui`) ouvre la **fenêtre de configuration** : connexion au NAS (URL, compte, mot de passe, test avec OTP et certificat), dossier photos, groupes gérés (choisis dans la liste du NAS), menu de l'Explorateur (entrées, Windows 11 / classique / les deux, installation et désinstallation) et synchronisation.

Le menu contextuel ouvre d'autres fenêtres, sans console :

- **Droits du dossier** : les groupes autorisés sur le dossier, à cocher ou décocher.
- **Synchronisation** : un aperçu des écarts entre fichiers et Synology Photos, puis application dans un sens ou dans l'autre après confirmation.

« Synchroniser fichiers → Photos » retire aussi les droits hérités du parent par les nouveaux dossiers.

## Commandes

```
synoRight [-c <dossierConfig>] [-v] <commande>
```

| Commande | Description |
|---|---|
| `init` | Assistant de configuration |
| `status` | Écarts entre fichiers de droits et Synology Photos, sans rien modifier |
| `pull [-n] [-y]` | Synology Photos → fichiers : crée/supprime les fichiers `.synoRight` |
| `push [-n] [-y]` | Fichiers → Synology Photos : applique les droits décrits par les fichiers |
| `show [dossier]` | Droits d'un dossier côté Photos et côté fichiers |
| `grant <groupe> [dossier]` | Donne le droit à un groupe sur un dossier (et son parent) |
| `revoke <groupe> [dossier]` | Retire le droit d'un groupe sur un dossier |
| `config` | Affiche la configuration et vérifie dossier, connexion et groupes |
| `groups` / `groups add` / `groups remove` | Liste / ajoute (parmi les groupes du NAS) / retire des groupes gérés |
| `password [--forget]` | Change (après vérification) ou oublie le mot de passe mémorisé |
| `logout` | Oublie la session Synology mémorisée |
| `rights [dossier]` | Choix des groupes autorisés sur un dossier (invite en console ; la fenêtre est ouverte par le menu de l'Explorateur ou `synoRightGui`) |
| `menu install [--modern\|--classic\|--all]` / `menu uninstall` | Menu contextuel de l'Explorateur Windows |
| `gui` | Ouvre l'interface graphique (c'est ce que lance `synoRightGui.exe`) |

`[dossier]` vaut par défaut le répertoire courant. `pull` et `push` affichent le détail des modifications puis demandent confirmation (`-y` pour appliquer directement, `-n` / `--dry-run` pour seulement simuler). Les dossiers pas encore indexés par Synology Photos sont signalés et ignorés.

Options communes : `-c` répertoire de configuration, `-v` mode verbeux (temps de traitement, stack traces), `-h` aide (aussi par commande : `synoRight pull -h`).

## Configuration

Par défaut dans `~/.synoRight/` (à défaut, une configuration présente dans le répertoire courant est utilisée ; `-c` pour un autre répertoire). Fichier `config.synoRight.properties`, éditable à la main (UTF-8, accents acceptés) :

```properties
folder=\\\\NAS\\photo
url=https://nas.example.com:5001
user=admin
# mot de passe chiffré (écrit par init / password), optionnel : demandé en console sinon
password=v2:...
# id:nom;id:nom : id (gid) du groupe sur le NAS et nom court, libre (nom des fichiers @<nom>.synoRight et des entrées du menu)
groups=65536:Kerdudou;65537:Godmez
# temps de traitement et détail des erreurs
verbose=false
# optionnel : empreinte SHA-256 du certificat auto-signé du NAS à accepter (proposé par init)
certFingerprint=AB:CD:...
# optionnel : entrées de chaque menu de l'Explorateur (rights, open, status, push, pull, groups, config) ; valeur par défaut : rights,status,push,config
# (l'ancienne clé commune menuEntries reste lue pour les deux menus)
menuEntries.modern=rights,status,push,config
menuEntries.classic=rights,status,push,pull,groups,config
# optionnel : modern, classic ou all (absent = Windows 11 si disponible, sinon classique)
menuTarget=modern
```

L'entrée `open` ouvre l'album du dossier dans Synology Photos ; elle est désactivée par défaut.

Le nom court d'un groupe peut différer de son nom sur le NAS (ex : `Kerdudou` pour le groupe « Famille Kerdudou ») : seul l'id le relie au NAS. Il se modifie dans la section Groupes de la fenêtre de configuration ; le changer pour un groupe déjà géré change le nom des fichiers attendus (les anciens `@<nom>.synoRight` ne sont plus pris en compte, « Photos → fichiers » recrée les nouveaux).

Les commandes qui modifient la configuration (`init`, `groups`, `password`, migrations) réécrivent le fichier : les commentaires ajoutés à la main ne sont pas conservés.

La session Synology (`cookieSession`, `synoToken`, `cookieDid`) est mémorisée à part, dans `session.synoRight.properties` du même répertoire : la configuration peut être copiée sur un autre poste sans y embarquer de session. Les anciennes configurations qui la contenaient sont migrées automatiquement.

Le mot de passe et la session sont chiffrés en AES-GCM avec une clé aléatoire propre au poste, stockée dans `~/.synoRight/key` (accès restreint au propriétaire). Si la clé est absente ou perdue, le mot de passe est simplement redemandé à la connexion (et la session recréée) ; pour utiliser la même configuration sur un autre poste, copier aussi le fichier de clé.

Si le mot de passe mémorisé est refusé par le NAS, il est redemandé et peut être mémorisé à la place.

**Certificat auto-signé** : si DSM présente un certificat non reconnu (certificat par défaut, auto-signé), `init` l'affiche avec son empreinte SHA-256 et propose de lui faire confiance. Seul ce certificat précis est alors accepté, en plus de la validation standard ; s'il change (renouvellement), la connexion échoue à nouveau : relancer `init`.

## Intégration Explorateur Windows

Le menu contextuel « Droits Synology Photos » (clic droit sur un dossier ou sur le fond d'un dossier ouvert) s'installe depuis la fenêtre de configuration (section Menu Explorateur) ou par `synoRight menu install [--modern|--classic|--all]` (proposé à la fin d'`init`) :

- **Windows 11** : le menu apparaît **au premier niveau** du clic droit. Il repose sur le package `synoRight` et la DLL `synoRightMenu.dll`. La première installation sur un poste demande une **autorisation administrateur** (une seule fois) pour approuver le certificat `CN=synoRight` qui signe le package. L'invite UAC affiche « Windows PowerShell » : elle importe `synoRight.cer` (du dossier de l'application) dans le magasin « Personnes autorisées » (TrustedPeople) de l'ordinateur.
- **Classique** : le menu est sous « Afficher plus d'options », écrit sous `HKCU` (pas de droits administrateur).
- Sans choix, Windows 11 est utilisé s'il est disponible, sinon le menu classique (`menuTarget`).

Les entrées sont au choix, **séparément pour chaque menu** (`menuEntries.modern`, `menuEntries.classic`) : droits du dossier, ouvrir dans Synology Photos, état de la synchro, les deux synchros complètes, ajout/retrait de chaque groupe configuré, configuration de synoRight. Les changements de groupes ou d'entrées sont pris en compte **sans réinstaller** (`groups add/remove` et la fenêtre de configuration mettent le menu à jour).

Le menu Windows 11 n'affiche pas de séparateurs entre les blocs d'entrées : c'est une limite du menu principal de Windows 11 pour les sous-menus des extensions (les séparateurs restent visibles dans le menu classique).

Le menu lance `synoRightGui.exe` : plus de fenêtre console depuis le menu. Les commandes générées pointent vers l'application à son emplacement courant : relancer l'installation du menu après un déplacement. Un menu installé par une ancienne version (syntaxe `-a ACTION`) affiche un message invitant à le réinstaller.
