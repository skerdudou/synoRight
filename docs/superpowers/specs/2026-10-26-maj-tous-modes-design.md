# Mise à jour dans tous les modes d'installation — conception

Demande de Steven (2026-10-26) : synoRight et Copybot se mettent à jour de la même façon, dans tous les modes d'installation, sans bibliothèque partagée (chacun recopie). Référence : spec Copybot `docs/superpowers/specs/2026-10-26-app-update-design.md` du dépôt copybot, sections 4 et 5, transmise par la session Copybot. Complète `2026-10-25-mises-a-jour-design.md` (lot 3). Pas de relecture de spec ni de plan : choix de Claude, à contester.

## Ce qui change

1. **Mode portable (zip)** : mis à jour par l'appli au lieu d'ouvrir la page de la release.
2. **Contrôle strict du téléchargement** : asset sans `digest` refusé ; taille différente de `size` = échec.
3. **Date de dernière vérification** enregistrée après toute vérification (réussie ou non), dans le dossier de configuration au lieu du registre.


**Écart voulu avec Copybot** : l'installateur de synoRight reste en installation pour l'utilisateur seul (décision de Steven) ; pas de choix « pour tous les utilisateurs ».

## Modes

Dossier de l'appli = parent de `jpackage.app-path`.

- **Installé** : `unins000.exe` présent ⇒ inchangé (setup `/SILENT /SUPPRESSMSGBOXES /NORESTART /CLOSEAPPLICATIONS /RELAUNCH`).
- **Portable** : app jpackage sans `unins000.exe` ⇒ asset `synoRight-<v>-windows-x64.zip`.
- **Autre** (jar, développement) ou asset absent ⇒ page de la release dans le navigateur (inchangé).

## Téléchargement et contrôle (tous modes)

- Release : pour le setup et le zip, `browser_download_url`, `size` et `digest` (`sha256:<hex>`).
- Dossier temporaire propre à la mise à jour (`Files.createTempDirectory("synoRight-update-")`), téléchargement en flux avec progression, annulation, délais existants (30 s d'en-têtes, 60 s sans octet).
- **Asset sans `digest` : refusé** (message lisible, rien n'est lancé) ; **taille reçue ≠ `size` : échec** ; empreinte fausse : échec.
- Échec, annulation : dossier temporaire supprimé, rien n'est remplacé, l'appli continue.

## Mode portable

- Décompression du zip dans le dossier temporaire : refus d'une entrée qui sort du dossier (zip slip) et d'un zip sans dossier racine `synoRight/` contenant `synoRightGui.exe` (le zip de la CI a ce dossier racine).
- **Autres instances** : avant de télécharger, si un autre processus synoRight tourne depuis le même dossier d'appli (`ProcessHandle.allProcesses()`, commande dans ce dossier, hors processus courant), la mise à jour est refusée avec un message « fermer les autres fenêtres synoRight » (fenêtres des droits ouvertes depuis l'Explorateur, synchronisation…).
- L'appli copie la ressource `apply-update.ps1` dans le dossier temporaire et la lance détachée : `powershell -NoProfile -ExecutionPolicy Bypass -File apply-update.ps1 -AppPid <pid> -AppDir <dossier de l'appli> -NewDir <dossier décompressé>\synoRight -Relaunch <dossier de l'appli>\synoRightGui.exe -LogDir <dossier de configuration>`, puis quitte (même sortie que pour le setup : garde des modifications non enregistrées d'abord).
- Le script :
  1. attend la fin du processus `AppPid` et de tout processus dont l'exécutable est dans `AppDir` (au plus 60 s ; sinon abandon sans rien toucher, journalisé, et relance) ;
  2. remplace **seulement l'image de l'appli** : `app` et `runtime` en miroir (`robocopy <new>\<d> <app>\<d> /MIR /R:2 /W:1`), puis les fichiers racine de la nouvelle version copiés sans rien supprimer à la racine (`robocopy <new> <app> /LEV:1 /R:2 /W:1`) : la configuration posée à côté de l'exe (`config.synoRight.properties`, `session…`), l'ancienne DLL du menu (versionnée, éventuellement verrouillée par l'Explorateur, nettoyée ensuite par la réparation des menus) et tout fichier de l'utilisateur restent ;
  3. dossier de l'appli non modifiable : la copie (étape 2) est relancée élevée (`Start-Process powershell -Verb RunAs -Wait`, paramètre `-CopyOnly`), une demande UAC ; le script non élevé garde la relance, pour que l'appli ne tourne jamais en administrateur ;
  4. code robocopy ≥ 8 : journalisé, relance quand même (pas de retour arrière automatique) ;
  5. relance `synoRightGui.exe` (la réparation automatique des menus au démarrage réenregistre le menu Windows 11 et nettoie l'ancienne DLL) ;
  6. supprime le dossier temporaire (sauf lui-même s'il ne peut pas) ; journal `update.log` dans le dossier de configuration (ajout, horodaté).
- Paramètre `-NoRelaunch` pour les tests.

## Vérification

- Au plus une vérification automatique par 24 h : dernière vérification absente, dans le futur ou d'il y a 24 h ou plus (inchangé).
- **Date enregistrée après toute vérification, réussie ou non** (automatique, manuelle, CLI `update`) : pas de nouvel essai à chaque démarrage hors ligne.
- **Stockage** : fichier `update.synoRight.properties` (clé `lastCheck`, secondes epoch) dans le dossier de configuration en vigueur ; un dossier portable le transporte avec sa configuration. L'ancienne valeur du registre (`HKCU\Software\synoRight\Update`) n'est plus lue ni écrite.
- Échec automatique : journalisé seulement ; manuelle : dit toujours le résultat ; version de développement : aucune vérification (inchangé).
- « Mettre à jour » n'interrompt jamais une opération en cours de la fenêtre de configuration (garde `busy` existante, message habituel).

## Installateur

Inchangé : installation pour l'utilisateur seul, sans droits administrateur.

## Transverse

- Textes par `Messages`, 5 langues ; CRLF (le script `.ps1` en UTF-8 avec BOM pour PowerShell 5.1) ; commentaires et commits en français.
- Tests : choix du mode et de l'asset, contrôle digest/taille, zip slip et dossier racine, commande de lancement du script, détection des autres instances (logique pure), date de vérification (fichier) ; le script testé sous Windows sur des dossiers temporaires (processus déjà terminé, `-NoRelaunch`) : miroir de `app`/`runtime`, fichiers racine copiés, fichiers de l'utilisateur conservés, journal écrit.
- README (fr, en) : mise à jour en mode zip.

## Laissé au constructeur

Découpage des classes du paquet `update`, noms des clés de messages, détails du script.
