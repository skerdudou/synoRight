# Aperçu des photos dans « Droits du dossier » — conception

Demande de Steven : pouvoir prévisualiser des photos du dossier dans la fenêtre « Droits du dossier », surtout avec plusieurs dossiers (savoir ce que contient un dossier avant d'en ouvrir l'accès). Choix validés en conversation : vignettes Synology Photos, repli sur les fichiers en plein format, sous-dossiers parcourus, 10 photos au plus, aperçu agrandi au clic. Pas de relecture de spec ni de plan : le reste est le choix de Claude.

## Comportement

- **Bandeau d'aperçus** en bas de la fenêtre, sous le tableau, au-dessus des notes : jusqu'à **10** vignettes d'environ 96 px côté, défilement horizontal si la fenêtre est étroite.
  - Un dossier : aperçus de ce dossier, chargés dès que les droits sont affichés.
  - Plusieurs dossiers : aperçus du dossier de la **ligne sélectionnée** (aucune ligne : texte « Sélectionner un dossier pour voir un aperçu de ses photos ») ; changer de ligne abandonne le chargement en cours et lance le nouveau.
  - Chargement en arrière-plan, vignette par vignette (cases d'attente puis images), sans jamais bloquer ni retarder le tableau des droits ; les aperçus d'un dossier déjà vu restent en mémoire tant que la fenêtre est ouverte.
  - Dossier sans photo : « Aucune photo dans ce dossier. » ; échec global (NAS injoignable…) : « Aperçu indisponible. » (détail en mode verbeux), jamais de boîte d'erreur.
- **Choix des 10 photos** : celles du dossier dans l'ordre de Synology Photos ; s'il y en a moins de 10, on complète avec les sous-dossiers (parcours en largeur, ordre de Photos), jusqu'à 10 ou épuisement.
- **Source de chaque vignette**, par ordre de préférence :
  1. vignette Synology Photos (taille `sm`) si elle est prête ;
  2. sinon, le fichier sur le partage (chemin local du dossier + nom du fichier), s'il est lisible par Java (JPEG, PNG, GIF, BMP) et fait au plus **20 Mo**, réduit à la taille de vignette (lecture sous-échantillonnée) ;
  3. sinon, une icône neutre avec le nom du fichier (HEIC/RAW/vidéo sans vignette, fichier trop gros ou illisible).
- **Dossier connu de Photos mais photos pas encore listées** (indexation en cours) : si l'API renvoie moins de 10 éléments pour le dossier et ses sous-dossiers, on complète avec les fichiers du partage (même dossier puis sous-dossiers, ordre alphabétique, extensions photo et vidéo courantes), sans doublon de nom de fichier. Les fichiers cachés et `@eaDir` (dossiers techniques du NAS) sont ignorés.
- **Aperçu agrandi** au clic sur une vignette : fenêtre modale, image ajustée à 80 % de l'écran au plus — vignette Photos `xl` si elle est prête, sinon le fichier en plein format (mêmes limites de format ; pas de limite de 20 Mo ici, mais lecture sous-échantillonnée à la taille d'affichage), sinon l'icône et le nom. Titre : nom du fichier ; flèches gauche/droite pour passer aux autres photos du bandeau, Échap ou clic pour fermer.
- Taille des fenêtres : la hauteur augmente de la hauteur du bandeau ; un seul dossier : largeur au moins 720 px.

## API Synology Photos (espace partagé)

Non documentée officiellement ; formes connues par la rétro-ingénierie publique, à vérifier sur le NAS (voir Diagnostic).

- Éléments d'un dossier : `SYNO.FotoTeam.Browse.Item`, méthode `list`, `folder_id`, `offset=0`, `limit`, `additional=["thumbnail"]` ; réponse `data.list[]` avec `id`, `filename`, `type` (`photo`, `video`, `live`), `additional.thumbnail` : `cache_key`, `unit_id`, et l'état par taille (`sm`, `m`, `xl` : `ready` ou autre).
- Sous-dossiers : `SYNO.FotoTeam.Browse.Folder` `list` existant (`listFolderPermissions(id)` en donne les `id` et noms).
- Vignette : `GET /webapi/entry.cgi?api=SYNO.FotoTeam.Thumbnail&method=get&version=…&mode=download&id=<unit_id>&type=unit&size=<sm|xl>&cache_key=<cache_key>` (+ jeton de session), réponse image JPEG ; une réponse JSON ou un statut ≠ 200 = vignette indisponible (repli).
- Les numéros de version des API sont des constantes faciles à changer.

## Diagnostic

Commande CLI cachée `synoRight previews <dossier>` : affiche, pour le dossier, les éléments choisis (au plus 10) avec leur source (`vignette`, `fichier`, `icône`) et la raison d'un repli, et le temps de chargement. Sert à valider l'API sur le vrai NAS et à diagnostiquer un aperçu vide.

## Non-objectifs

Aperçu dans la fenêtre de configuration ou de synchronisation ; lecture des vidéos ; décodage HEIC/RAW en Java ; cache disque des vignettes ; réglage du nombre de photos.

## Anti-objectifs

Ralentir l'ouverture ou l'application des droits ; une boîte d'erreur à cause d'un aperçu ; télécharger des fichiers en plein format pour remplir le bandeau au-delà de la limite de 20 Mo ; plus de 10 éléments chargés par dossier.

## Transverse

- Textes visibles par `Messages`, 5 langues ; CRLF ; commentaires et commits en français.
- Tests : choix des éléments et des sources (API, repli fichiers, sous-dossiers, limite, doublons) en logique pure ; client API sur le faux NAS des tests (`FakeDsm`, nouvelles réponses `Browse.Item` et `Thumbnail`) ; lecture/réduction d'image sur des fichiers temporaires ; pas de réseau réel.

## Laissé au constructeur

Noms des classes, disposition fine du bandeau et de l'aperçu, icône neutre, nombre de fils de chargement (un seul suffit).
