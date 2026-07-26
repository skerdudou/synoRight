# synoRight

Outil en ligne de commande pour synchroniser les droits d'accès des albums **Synology Photos** (espace partagé, API `SYNO.FotoTeam`) avec des fichiers marqueurs posés sur le partage réseau du NAS.

Principe : un dossier photo porte un fichier caché `@<groupe>.synoRight` pour chaque groupe autorisé. L'outil compare ces fichiers avec les permissions réelles de Synology Photos et applique le delta, dans un sens ou dans l'autre. Il gère 2 niveaux de dossiers sous la racine photo.

## Utilisation

```
java -jar synoRight.jar -a <action> [-c <dossierConfig>] [-d <répertoire>] [-g <groupe>]
```

| Option | Description |
|---|---|
| `-a, --action` | Action à exécuter (obligatoire), voir ci-dessous |
| `-c, --config` | Répertoire contenant la configuration (défaut : répertoire courant) |
| `-d, --directory` | Répertoire ciblé (pour `ADD_GRP` / `DEL_GRP`) |
| `-g, --group` | Nom du groupe à ajouter / supprimer (pour `ADD_GRP` / `DEL_GRP`) |
| `-y, --yes` | Applique les synchros complètes sans demander de confirmation |

Actions :

- `INIT` : crée le fichier de configuration en posant des questions (URL, compte, groupes...), teste le login et vérifie que les groupes configurés existent sur le NAS
- `TEST_CONNEXION` : login + lecture complète FS et Synology Photos, sans modification
- `FULL_DB_TO_FS` : crée/supprime les fichiers `.synoRight` sur le NAS d'après les droits Synology Photos
- `FULL_FS_TO_DB` : applique dans Synology Photos les droits décrits par les fichiers `.synoRight`
- `ADD_GRP` / `DEL_GRP` : ajoute/enlève le droit d'un groupe sur un répertoire donné (et son parent pour l'ajout)
- `INSTALL_MENU` / `UNINSTALL_MENU` : installe/désinstalle le menu contextuel de l'Explorateur Windows (voir plus bas)

Les deux synchros complètes affichent le détail des modifications puis demandent confirmation avant d'appliquer quoi que ce soit (sauf avec `-y`).

Au premier login, le mot de passe (si absent de la configuration) et le code OTP sont demandés en console ; la session (`cookieSession`, `synoToken`, `cookieDid`) est ensuite mémorisée dans le fichier de configuration.

## Configuration

Fichier `config.synoRight.properties` dans le dossier passé via `-c` :

```properties
folder=\\\\NAS\\photo
url=https://nas.example.com:5001
user=admin
# mot de passe chiffré (généré par INIT), optionnel : demandé en console sinon
password=...
# id:nom;id:nom
groups=3:famille;5:amis
```

Le mot de passe est chiffré en AES-GCM avec une clé aléatoire propre au poste, stockée dans `~/.synoRight/key` (accès restreint au propriétaire). Les configurations à l'ancien format sont re-chiffrées automatiquement à la première lecture. Si la clé est absente ou perdue, le mot de passe est simplement redemandé à la connexion ; pour utiliser la même configuration sur un autre poste, copier aussi le fichier de clé.

## Build

```
mvn package
```

Produit `target/synoRight.jar` (jar exécutable autonome). Nécessite Java 25+.

## Intégration Explorateur Windows

```
java -jar synoRight.jar -a INSTALL_MENU -c <dossierConfig>
```

Installe un menu contextuel « Droits Synology Photos » (clic droit sur un dossier ou sur le fond d'un dossier ouvert) pour l'utilisateur courant — écrit sous `HKCU`, pas besoin de droits administrateur. Le menu propose les deux synchros complètes et l'ajout/suppression de chaque groupe configuré sur le dossier ciblé. Les commandes générées utilisent le java courant, le chemin du jar et celui de la configuration : à lancer depuis l'emplacement définitif du jar, et à relancer après un changement de groupes ou un déplacement du jar. `-a UNINSTALL_MENU` retire le menu.
