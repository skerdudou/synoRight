# Sélection de plusieurs dossiers — conception

Spec rédigée après l'implémentation (pas de phase de brainstorming préalable) : elle consigne les choix faits, à contester le cas échéant.

## Besoin

Pouvoir sélectionner plusieurs dossiers dans l'Explorateur et lancer les actions du menu sur tous, en particulier « Droits du dossier », qui doit présenter un tableau pour cocher les droits des différents dossiers.

## Comportement

- **Droits du dossier…** avec un seul dossier : fenêtre inchangée (une ligne par groupe, état Photos et fichier).
- Avec plusieurs dossiers : tableau **dossiers × groupes**, une case par cellule, initialisée sur l'état Synology Photos.
  - Clic sur l'en-tête d'un groupe : coche toute la colonne, ou la décoche si elle était entièrement cochée.
  - Cocher un groupe sur un sous-dossier le coche sur son parent s'il est dans le tableau (le moteur donne de toute façon le droit au parent) ; le décocher sur un parent le décoche sur ses sous-dossiers du tableau (un sous-dossier autorisé sous un parent interdit n'est pas navigable).
  - Écart Photos/fichier : cellule teintée, nom du dossier en couleur d'avertissement, info-bulle avec les deux états.
  - Application : dossiers de premier niveau d'abord ; un dossier en erreur n'empêche pas les autres, le compte rendu liste modifications et erreurs.
  - Dossiers hors périmètre (hors du dossier photos, 3e niveau, non indexés) : écartés et signalés à l'ouverture ; erreur seulement si aucun n'est gérable.
- **Ajouter / Retirer &lt;groupe&gt;** et **Ouvrir dans Synology Photos** : appliqués à chaque dossier (confirmation au-delà de 5 onglets à ouvrir).
- CLI : `show`, `grant`, `revoke`, `rights` acceptent plusieurs dossiers (arrêt à la première erreur, code de sortie 1, comme avant).

## Transmission de la sélection

- **Menu Windows 11** (DLL `IExplorerCommand`) : `Invoke` reçoit toute la sélection ; l'argument `"%V"` est remplacé par un argument entre guillemets par dossier. L'entrée n'apparaît que si tous les éléments sont sous le dossier photos. Limite : ligne de commande Windows de 32 767 caractères (plusieurs centaines de dossiers).
- **Menu classique** (verbes statiques du registre) : l'Explorateur lance un processus par dossier (15 au plus). Les commandes portant sur des dossiers reçoivent l'option cachée `--collect` :
  - le premier processus (`SelectionCollector`) ouvre un port sur la boucle locale et publie « port jeton » dans `%TEMP%\synoRight-<hash action>.collect` par renommage atomique ;
  - les suivants y envoient jeton et dossiers, attendent un accusé de réception puis s'arrêtent sans fenêtre ;
  - le collecteur s'arrête après 1,5 s sans nouvel envoi (20 s au plus), retire le fichier et traite l'ensemble ;
  - fichier obsolète (collecteur arrêté) : supprimé et remplacé ; envoi sans accusé : nouvel essai ; au pire, une seconde fenêtre, jamais de dossier perdu.
  - Coût : environ 1,5 s de délai sur chaque action du menu classique portant sur des dossiers.

## Alternatives écartées

- Menu « Envoyer vers » (une seule ligne de commande avec tous les fichiers) : hors du sous-menu synoRight, une entrée par action.
- `MultiSelectModel=Player` pour dépasser 15 dossiers dans le menu classique : un processus Java par dossier reste coûteux ; le menu Windows 11 n'a pas cette limite.
- Fichier d'arguments `@fichier` de picocli depuis la DLL : échappement des chemins Windows délicat, pas nécessaire en dessous de plusieurs centaines de dossiers.
