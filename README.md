# synoRight

Outil en ligne de commande pour synchroniser les droits d'accès des albums **Synology Photos** (espace partagé, API `SYNO.FotoTeam`) avec des fichiers marqueurs posés sur le partage réseau du NAS.

Principe : un dossier photo porte un fichier caché `@<groupe>.synoRight` pour chaque groupe autorisé. L'outil compare ces fichiers avec les permissions réelles de Synology Photos et applique le delta, dans un sens ou dans l'autre. Il gère 2 niveaux de dossiers sous la racine photo.

## Installation

1. Construire l'application autonome (runtime Java embarqué, rien à installer sur le poste) :
   ```
   mvn package -Pexe
   ```
   puis copier le dossier `target/dist/synoRight/` à son emplacement définitif (ex : `C:\Program Files\synoRight`).
2. Lancer l'assistant :
   ```
   synoRight init
   ```
   Il demande l'URL de DSM et le compte, **teste la connexion tout de suite** (OTP compris), puis le dossier photos (vérifié), fait choisir les groupes **dans la liste des groupes du NAS**, et propose d'installer le menu contextuel de l'Explorateur.

Relancer `init` sur une configuration existante propose les valeurs actuelles, mot de passe compris (Entrée pour les conserver). La session mémorisée n'est remplacée qu'à l'enregistrement : abandonner l'assistant ne change rien.

Sans le profil `exe`, `mvn package` produit `target/synoRight.jar`, utilisable avec `java -jar synoRight.jar <commande>` (Java 25+).

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
| `menu install` / `menu uninstall` | Menu contextuel de l'Explorateur Windows |

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
# id:nom;id:nom (ids des groupes du NAS)
groups=3:famille;5:amis
# temps de traitement et détail des erreurs
verbose=false
# optionnel : empreinte SHA-256 du certificat auto-signé du NAS à accepter (proposé par init)
certFingerprint=AB:CD:...
```

Les commandes qui modifient la configuration (`init`, `groups`, `password`, migrations) réécrivent le fichier : les commentaires ajoutés à la main ne sont pas conservés.

La session Synology (`cookieSession`, `synoToken`, `cookieDid`) est mémorisée à part, dans `session.synoRight.properties` du même répertoire : la configuration peut être copiée sur un autre poste sans y embarquer de session. Les anciennes configurations qui la contenaient sont migrées automatiquement.

Le mot de passe et la session sont chiffrés en AES-GCM avec une clé aléatoire propre au poste, stockée dans `~/.synoRight/key` (accès restreint au propriétaire). Si la clé est absente ou perdue, le mot de passe est simplement redemandé à la connexion (et la session recréée) ; pour utiliser la même configuration sur un autre poste, copier aussi le fichier de clé.

Si le mot de passe mémorisé est refusé par le NAS, il est redemandé et peut être mémorisé à la place.

**Certificat auto-signé** : si DSM présente un certificat non reconnu (certificat par défaut, auto-signé), `init` l'affiche avec son empreinte SHA-256 et propose de lui faire confiance. Seul ce certificat précis est alors accepté, en plus de la validation standard ; s'il change (renouvellement), la connexion échoue à nouveau : relancer `init`.

## Intégration Explorateur Windows

`synoRight menu install` (proposé à la fin d'`init`) installe un menu contextuel « Droits Synology Photos » (clic droit sur un dossier ou sur le fond d'un dossier ouvert) pour l'utilisateur courant — écrit sous `HKCU`, pas besoin de droits administrateur. Il propose : voir les droits du dossier, état de la synchro, les deux synchros complètes, et l'ajout/suppression de chaque groupe configuré.

La fenêtre ouverte par le menu attend Entrée avant de se fermer (option `--pause`, ajoutée par le menu ; sans elle, l'outil rend la main directement).

Les commandes générées pointent vers `synoRight.exe` (ou java + jar) à son emplacement courant : relancer `menu install` après un déplacement. `groups add/remove` met le menu à jour automatiquement. Un menu installé par une ancienne version (syntaxe `-a ACTION`) affiche un message invitant à le réinstaller.
