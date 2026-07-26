# PhotostationAdmin (synoRight)

Outil en ligne de commande pour synchroniser les droits d'accès des albums **Synology Photos** (espace partagé, API `SYNO.FotoTeam`) avec des fichiers marqueurs posés sur le partage réseau du NAS.

Principe : un dossier photo porte un fichier caché `@<groupe>.synoRight` pour chaque groupe autorisé. L'outil compare ces fichiers avec les permissions réelles de Synology Photos et applique le delta, dans un sens ou dans l'autre. Il gère 2 niveaux de dossiers sous la racine photo.

## Utilisation

```
java -jar PhotostationAdmin-<version>-shaded.jar -a <action> [-c <dossierConfig>] [-d <répertoire>] [-g <groupe>]
```

| Option | Description |
|---|---|
| `-a, --action` | Action à exécuter (obligatoire), voir ci-dessous |
| `-c, --config` | Répertoire contenant la configuration (défaut : répertoire courant) |
| `-d, --directory` | Répertoire ciblé (pour `ADD_GRP` / `DEL_GRP`) |
| `-g, --group` | Nom du groupe à ajouter / supprimer (pour `ADD_GRP` / `DEL_GRP`) |

Actions :

- `INIT` : crée le fichier de configuration en posant des questions (URL, compte, groupes...), teste le login et vérifie que les groupes configurés existent sur le NAS
- `TEST_CONNEXION` : login + lecture complète FS et Synology Photos, sans modification
- `FULL_DB_TO_FS` : crée/supprime les fichiers `.synoRight` sur le NAS d'après les droits Synology Photos
- `FULL_FS_TO_DB` : applique dans Synology Photos les droits décrits par les fichiers `.synoRight`
- `ADD_GRP` / `DEL_GRP` : ajoute/enlève le droit d'un groupe sur un répertoire donné (et son parent pour l'ajout)

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

## Build

```
mvn package
```

Produit `target/PhotostationAdmin-<version>-shaded.jar` (jar exécutable autonome). Nécessite Java 25+.

## Intégration Explorateur Windows

`src/test/resources/install.reg` donne un exemple d'entrées de registre pour ajouter les actions au menu contextuel des dossiers (chemins de jar et de JRE à adapter).
