# Mises à jour facilitées — conception

Demande de Steven : faciliter les mises à jour de synoRight, sans oublier les menus de l'Explorateur (classique et Windows 11). Trois lots validés sans relecture de spec ni de plan : les choix ci-dessous sont ceux de Claude, à contester le cas échéant.

## Constat

- Mise à jour actuelle : télécharger le zip, fermer l'appli, remplacer le dossier (souvent `C:\Program Files`, droits administrateur), puis penser à réinstaller le menu.
- Menu Windows 11 : `synoRightMenu.dll` est chargée par le substitut COM (`dllhost.exe`) dès le premier clic droit, le fichier est verrouillé ; le package sparse reste enregistré dans son ancienne version (manifeste périmé) tant que le menu n'est pas réinstallé ; un certificat différent redemande l'approbation administrateur.
- Menu classique et clé `HKCU\Software\synoRight\Menu` de la DLL : chemins du lanceur et arguments figés au moment de l'installation ; une nouvelle version qui change les arguments, ou un autre dossier d'installation, laisse des menus périmés ou cassés.
- Aucun désinstalleur : supprimer le dossier laisse menus, package et certificat.

## Objectifs

1. Après toute mise à jour (installateur, zip décompressé par-dessus ou ailleurs), les menus installés sont remis en cohérence avec la version et l'emplacement courants sans action de l'utilisateur.
2. Un installateur par utilisateur (sans droits administrateur) qui installe, met à jour sur place et désinstalle proprement, menus compris.
3. L'IHM signale une nouvelle version et sait l'installer en un clic.

Non-objectifs : mise à jour silencieuse en arrière-plan sans l'utilisateur ; signature Authenticode reconnue par SmartScreen (certificat payant) ; Store / MSIX complet ; macOS / Linux.

Anti-objectifs : une UAC ou une fenêtre inattendue à chaque lancement ; des actions du menu ralenties de façon perceptible ; un menu qui disparaît pendant la mise à jour ; une réparation qui tourne en boucle quand elle échoue.

## Lot 1 — réparation automatique des menus

### Repère d'installation

Clé `HKCU\Software\synoRight\Install`, écrite après chaque installation ou réparation réussie de menu (`MenuService.install`, réparation). `MenuService.refresh` ne réinstalle pas le package Windows 11 : il ne met à jour que `ConfigDir` d'un repère existant (version, lanceur, empreinte et tentative gardés) et n'en crée pas :

- `Version` : version de l'application (`Implementation-Version`) ;
- `Launcher` : commande du lanceur de l'IHM (`CommandLines.join(Launchers.gui())`) ;
- `ConfigDir` : dossier de configuration utilisé par les menus ;
- `CertThumbprint` : empreinte du certificat approuvé (menu Windows 11 seulement) ;
- `AttemptedVersion` : dernière version pour laquelle une réparation automatique a été tentée (évite de réessayer à chaque lancement après un échec).

`ModernMenuInstaller.uninstall` ne supprime plus que `HKCU\Software\synoRight\Menu` ; `MenuService.uninstall` supprime toute la clé `HKCU\Software\synoRight`.

### Détection

`MenuUpdater.needsRepair()` (nom indicatif) : lecture du repère par un seul `reg query` ; réparation nécessaire si un menu est installé et que :

- la version ou le lanceur diffère de l'application courante ;
- ou le repère est absent alors qu'un menu existe (installations antérieures à ce lot) : présence de la clé du menu classique (`Directory\shell\01SynoRight`) ou de `HKCU\Software\synoRight\Menu`. Le dossier de configuration est alors lu dans la valeur `ConfigDir` de la clé `Menu`, à défaut dans l'argument `-c` de la commande du menu classique, à défaut le dossier par défaut.

Pas de réparation en développement (version inconnue). Pas de réparation automatique (démarrage de l'IHM, `--if-needed`) depuis le jar quand le menu Windows 11 est configuré : le package ne peut pas être installé sans l'exe, les menus ne sont pas repointés vers cette copie (`menu repair` forcé échoue avec le message dédié).

### Réparation

- Charge la configuration du `ConfigDir` du repère, puis réinstalle les menus présents : la cible est déduite de ce qui est installé (classique, Windows 11, ou les deux), pas de la configuration, pour ne pas installer un menu que l'utilisateur avait retiré.
- Menu Windows 11 : si le package installé a le même `AppDir` (`InstallLocation` du package) et une version inférieure, `Add-AppxPackage` met à jour sur place (pas de retrait préalable : le menu ne disparaît pas) ; sinon retrait puis ajout, comme aujourd'hui.
- Approbation du certificat : seulement si l'empreinte a changé (comportement actuel de `trustCertificate`).
- Nettoyage : suppression des `synoRightMenu*.dll` du dossier de l'application autres que celle de la version courante ; un fichier verrouillé est ignoré (retenté à la réparation suivante, sinon laissé).
- Verrou : le menu classique lance jusqu'à 15 processus à la fois ; un verrou de fichier (`%TEMP%\synoRight-repair.lock`, `tryLock`) fait qu'un seul répare, les autres continuent sans attendre.
- Échec : `AttemptedVersion` est quand même écrit, l'erreur est montrée une fois (sauf pour l'exécution `--if-needed` de l'installateur, qui ne note pas la tentative : le prochain démarrage de l'IHM réessaie et montre l'erreur une fois) ; `synoRight menu repair` permet de relancer.

### Déclenchement

- Au démarrage de toute commande de l'IHM (`gui`, fenêtres des droits, synchro, ajout/retrait…), avant la fenêtre : coût sans réparation limité à un `reg query`. Pendant une réparation, une petite fenêtre « Mise à jour des menus de l'Explorateur… » ; une erreur s'affiche dans une boîte de dialogue, puis la commande continue.
- `synoRight menu repair` (CLI) : réparation forcée (ignore `AttemptedVersion`), message de résultat ; sans menu installé : « aucun menu installé », code 0. Option cachée `--if-needed` pour l'installateur (ne fait rien si le repère est à jour ; la tentative n'est pas notée : l'installateur ignore le code de sortie, un échec est retenté et affiché par l'IHM relancée).
- Pas de réparation automatique depuis les autres commandes CLI (scripts, appels d'outils : pas d'UAC inattendue).

### DLL au nom versionné

- `build.ps1` produit `synoRightMenu-<version Maven>.dll` et remplace `@DLL@` dans `Path=` du manifeste (`AppxManifest.xml`).
- `ModernMenuInstaller` attend `synoRightMenu-<version>.dll` (version de l'application) ; l'ancien nom `synoRightMenu.dll` n'est plus produit.
- Effet : une nouvelle version ne remplace jamais une DLL verrouillée par `dllhost.exe`.

## Lot 2 — installateur

- **Inno Setup 6**, script `native/installer/synoRight.iss`, compilé en CI (`choco install innosetup`) ; localement par `ISCC` si installé (documenté dans le README, pas dans le build Maven).
- Installation **par utilisateur** : `PrivilegesRequired=lowest`, `DefaultDirName={autopf}\synoRight` (= `%LOCALAPPDATA%\Programs\synoRight`), pas de page de choix du dossier lors d'une mise à jour.
- `AppId` fixe : une nouvelle version met à jour sur place. `CloseApplications=yes` (Restart Manager) pour fermer synoRight.
- Raccourci « synoRight » dans le menu Démarrer vers `synoRightGui.exe` ; icône de bureau en option décochée.
- Langues de l'assistant : anglais, français, allemand, espagnol, italien.
- Fin d'installation : `synoRight.exe menu repair --if-needed` (caché, attendu), puis case « Lancer synoRight » (cochée ; exécutée aussi en mode silencieux quand le paramètre `/RELAUNCH` est passé, pour la mise à jour depuis l'IHM).
- Désinstallation : `synoRight.exe menu uninstall` (caché, attendu) avant la suppression des fichiers ; la configuration de l'utilisateur est conservée. Les `synoRightMenu-*.dll` encore verrouillées peuvent rester (limite connue, sans conséquence).
- CI : la release publie `synoRight-<version>-setup.exe` en plus du zip (portable) et du jar ; les notes d'installation recommandent le setup. Pré-version seulement si la version contient `-` (`0.4-beta`) ; la release reste en brouillon, publiée à la main.

## Lot 3 — nouvelle version disponible

- **Vérification** : à l'ouverture de la fenêtre de configuration, en arrière-plan, au plus une fois par 24 h (horodatage `LastCheck` dans `HKCU\Software\synoRight\Update`), par `GET https://api.github.com/repos/skerdudou/synoRight/releases/latest` (`java.net.http`, proxy système, délai 10 s). Toute erreur réseau est ignorée silencieusement (journal en mode verbeux).
- Réglage « Vérifier les mises à jour au démarrage » (case dans la fenêtre de configuration, clé de configuration `updateCheck`, vrai par défaut).
- Comparaison : `tag_name` sans `v`, comparaison numérique par segments ; une version avec suffixe (`-beta`) est inférieure à la même sans suffixe. Version de développement : pas de vérification.
- **Bandeau** en haut de la fenêtre de configuration : « synoRight x.y est disponible. » avec les boutons « Mettre à jour » et « Notes de version » (ouvre `html_url`). Bouton « Rechercher les mises à jour » dans la barre du bas, à côté de la version (vérification immédiate, résultat affiché même « à jour »).
- **Mettre à jour** :
  - application installée par l'installateur (`unins000.exe` présent dans le dossier de l'application) : télécharge l'asset `synoRight-<version>-setup.exe` dans `%TEMP%` avec progression, vérifie le `digest` SHA-256 fourni par l'API s'il existe, lance `setup.exe /SILENT /SUPPRESSMSGBOXES /NORESTART /CLOSEAPPLICATIONS /RELAUNCH` puis ferme l'application (après la confirmation habituelle s'il y a des modifications non enregistrées) ;
  - application portable (zip), jar ou asset absent : ouvre la page de la release dans le navigateur.
- CLI : `synoRight update` affiche la version courante et la dernière disponible (pas d'installation depuis la CLI).

## Transverse

- Tous les nouveaux textes passent par `Messages` dans les 5 fichiers `messages*.properties`.
- Fichiers en CRLF, commentaires et commits en français.
- README (fr et en) : installation par le setup, mise à jour, désinstallation, portable ; construction de l'installateur ; CI.
- Tests : logique pure testée (détection, construction des commandes PowerShell/reg, comparaison de versions, analyse de la réponse GitHub, choix du mode de mise à jour) sans exécuter reg/PowerShell ni réseau ; les appels système passent par des fonctions injectables comme dans le code existant.

## Laissé au constructeur

Noms exacts des classes et clés de messages, découpage en commits, disposition fine du bandeau, détails du script Inno Setup non fixés ci-dessus.
