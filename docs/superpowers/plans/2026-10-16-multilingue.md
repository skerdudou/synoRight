# Multilinguisme — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Traduire tous les textes vus par l'utilisateur de synoRight en anglais (référence, défaut), français, allemand, espagnol et italien.

**Architecture:** Fichiers `i18n/messages*.properties` (UTF-8) lus par une classe `Messages` (substitution `{n}` maison) ; langue résolue au démarrage (`Language.resolve` : configuration `language`, sinon langue d'affichage du système, sinon anglais) ; textes externalisés domaine par domaine ; picocli branché sur le même bundle pour l'aide ; titre racine du menu Windows 11 lu par la DLL dans le registre.

**Tech Stack:** Java 25, picocli 4.7.7 (ResourceBundle), Swing/FlatLaf, JUnit 6, C++ (DLL), Maven.

**Spec:** `docs/superpowers/specs/2026-10-16-multilingue-design.md`

## Global Constraints

- Répertoire : `C:\Users\Steven\IdeaProjects\PhotostationAdmin\.claude\worktrees\ihm-menu-win11` (worktree, branche `worktree-ihm-menu-win11`) ; commandes git/mvn simples, une par appel (pas de boucles bash, pas de `cd x && git`, pas de redirection avec `$VAR`).
- Fichiers texte en **CRLF** : normaliser avant commit avec `sed -i 's/\r$//; s/$/\r/' <fichiers>` et vérifier `git diff --stat` (pas de réécriture complète). Fichiers `.properties` : UTF-8 sans BOM, CRLF.
- Code : tabulations, `final` sur paramètres et variables locales, commentaires et Javadoc **en français** (inchangé), messages de commit en français avec accents terminés par `Co-Authored-By: <modèle réel> <noreply@anthropic.com>`.
- Textes utilisateur : **jamais en dur** après externalisation : `Messages.get("clé", args…)`. Anglais = `messages.properties` ; chaque clé ajoutée l'est aussi dans `messages_fr.properties` (texte français actuel, à l'identique) ; DE/ES/IT ajoutés en Task 7 (avant : le repli anglais s'applique).
- Paramètres `{0}`, `{1}`… ; apostrophes écrites telles quelles (pas de `MessageFormat`).
- Clés : `domaine.sujet.detail` en minuscules camelCase après le domaine (ex. `engine.status.synchronized`, `gui.config.save.done`). Domaines : `main`, `ui`, `prefs`, `synology`, `engine`, `menu`, `cli`, `gui`.
- Ne pas traduire : logs de debug/temps (« Login time », « Total time », `LogUtil.debug`), contenu technique (.reg, commandes PowerShell, noms d'API), « Synology Photos », « DSM », « NAS », « OTP ».
- Les tests tournent en anglais (`synoRight.language=en` via surefire) : adapter les assertions de textes.
- Ne jamais lancer d'installation de menu, d'import de certificat, d'écriture dans le registre ou de fenêtre réelle ; ne pas toucher `D:\progs\autre\synoRight` ni `%USERPROFILE%\.synoRight`.
- `mvn -B -q test` doit passer à la fin de chaque tâche.
- Glossaire (spec) : droits = permissions / Berechtigungen / permisos / autorizzazioni ; espace partagé = Shared Space / Gemeinsamer Bereich / Espacio compartido / Spazio condiviso ; dossier photos = photo folder / Fotoordner / carpeta de fotos / cartella foto ; synchronisation = synchronization / Synchronisierung / sincronización / sincronizzazione ; groupe = group / Gruppe / grupo / gruppo ; nom court = short name / Kurzname / nombre corto / nome breve ; fichiers de droits = permission files / Berechtigungsdateien / archivos de permisos / file di autorizzazione ; menu classique = classic menu / klassisches Menü / menú clásico / menu classico ; Afficher plus d'options = Show more options / Weitere Optionen anzeigen / Mostrar más opciones / Mostra altre opzioni.

## Review Focus

1. **Langue lue avant l'aide** : `synoRight -c <dir> --help` avec `language=fr` dans `<dir>` affiche l'aide en français ; sans configuration : langue du système ou anglais. Test : `MainTest.langueDeLaConfiguration_avantLAide` (Task 1/6).
2. **Fichiers de traduction incohérents** (clé manquante, `{1}` oublié dans une langue) : détecté par `MessagesTest.memesClesEtParametres` (Task 1, actif dès Task 7 pour DE/ES/IT ; avant, comparer EN/FR).
3. **Apostrophes et accents** : `l'album '{0}'` rendu intact ; console CP850 : les caractères allemands/espagnols (ß, ñ, ¿, ¡) existent en CP850. Test : `MessagesTest.apostropheConservee` (Task 1).
4. **Menu Windows 11 dans une ancienne installation sans `RootTitle`** : la DLL affiche « Synology Photos ». Vérifié par lecture du code (Task 5).
5. **Changement de langue dans la fenêtre** : la configuration est enregistrée et les menus réécrits dans la nouvelle langue. Test : `ConfigFormTest` (langue dans l'état modifié/enregistré, Task 4) + `MenuDefinitionTest` en anglais/français (Task 5).

---

### Task 1: Infrastructure i18n

**Files:** Create `src/main/java/sk/synoAdmin/i18n/Language.java`, `Messages.java`, `src/main/resources/i18n/messages.properties`, `messages_fr.properties`, `messages_de.properties`, `messages_es.properties`, `messages_it.properties` (les trois derniers vides sauf un commentaire pour l'instant), `src/test/java/sk/synoAdmin/i18n/MessagesTest.java`. Modify `prefs/Preferences.java` (clé `language`), `Main.java` (résolution précoce), `pom.xml` (surefire `synoRight.language=en`).

**Interfaces produites :** `Language` (EN, FR, DE, ES, IT ; `String code()`, `static Optional<Language> fromCode(String)`, `static Language resolve(String setting)`) ; `Messages.get(String key, Object... args)`, `Messages.setLanguage(Language)`, `Messages.language()`, `Messages.bundle()` ; `Preferences.getLanguageSetting()` (`"auto"` par défaut) / `setLanguageSetting(String)` / `static String readLanguageSetting(Path configDir)`.

- [ ] Tests d'abord (`MessagesTest`) :
  - `substitution` : clé de test `test.params=A {0} B {1}` (ajoutée aux 5 fichiers) → `Messages.get("test.params", "x", 2)` = `A x B 2`.
  - `apostropheConservee` : clé `test.apostrophe=l'album '{0}'` → `l'album 'X'`.
  - `cleAbsente_repliAnglaisPuisCle` : clé présente seulement en anglais lue en FR → texte anglais ; clé inexistante → `!cle.inexistante!`.
  - `resolve` : `resolve("fr")` = FR ; `resolve("auto")`/`resolve(null)`/`resolve("xx")` = langue système si supportée sinon EN (tester avec `Locale.setDefault(Locale.Category.DISPLAY, …)` restauré après, et la propriété `synoRight.language` temporairement retirée) ; propriété `synoRight.language=de` prioritaire.
  - `memesClesEtParametres` : pour FR (et DE/ES/IT s'ils contiennent au moins une clé hors `test.*`), mêmes clés que l'anglais et mêmes ensembles `{n}` par clé. Charger les fichiers comme `Properties` UTF-8 directement depuis le classpath (pas via ResourceBundle, qui masque les clés absentes par le repli).
- [ ] `Messages` : bundle `i18n/messages` chargé avec `ResourceBundle.getBundle("i18n.messages", locale, ResourceBundle.Control.getNoFallbackControl(FORMAT_PROPERTIES))` pour la langue, + bundle anglais (racine) pour le repli ; substitution `{n}` par remplacement de chaînes ; `String.valueOf(arg)`.
- [ ] `Language.resolve` : propriété système `synoRight.language` d'abord (si code valide), puis `setting` si code valide, puis `Locale.getDefault(Locale.Category.DISPLAY).getLanguage()` si supporté, sinon EN.
- [ ] `Preferences` : champ `languageSetting = "auto"`, lu (`language`, `auto` si absent/inconnu) et toujours écrit par `save()` ; commentaire d'en-tête du fichier complété (`language=auto|en|fr|de|es|it`). `readLanguageSetting(Path dir)` : lit seulement la clé dans `config.synoRight.properties` via `PropertiesFiles.load`, renvoie `auto` sur toute erreur, sans rien afficher.
- [ ] `Main.run` : avant de construire `CommandLine`, trouver `-c`/`--config` (valeur suivante, ou `--config=valeur`) dans `args`, `Preferences.resolveConfigDir(...)`, `Messages.setLanguage(Language.resolve(Preferences.readLanguageSetting(dir)))`. Test `MainTest.langueDeLaConfiguration` : config temporaire avec `language=fr` (et propriété système `synoRight.language` retirée le temps du test puis restaurée) → après `Main.run(new String[] {"-c", dir, "--version"})`, `Messages.language()` = FR (remettre EN après).
- [ ] `pom.xml` surefire `systemPropertyVariables` : `<synoRight.language>en</synoRight.language>`.
- [ ] `mvn -B -q test`, CRLF, commit « i18n : infrastructure (Messages, langues, clé language) ».

### Task 2: Externaliser le cœur (main, ui, prefs, synology, engine, menu installers)

**Files:** Modify every user-facing string in `Main.java`, `ConsoleUtil.java`, `ui/ConsoleInteraction.java`, `prefs/*.java`, `synology/*.java`, `engine/*.java`, `menu/ClassicMenuInstaller.java`, `menu/ModernMenuInstaller.java`, `menu/MenuService.java`, `menu/Launchers.java` (pas les libellés de menu : Task 5) ; `messages.properties` + `messages_fr.properties` ; tests de ces paquets.

- [ ] Recenser les chaînes (`grep -n '"' fichier`), créer les clés (`main.*`, `ui.*`, `prefs.*`, `synology.*`, `engine.*`, `menu.install.*`), anglais naturel dans `messages.properties`, texte français **actuel à l'identique** dans `messages_fr.properties`.
- [ ] Remplacer par `Messages.get(...)`. Les invites oui/non de `ConsoleInteraction` : réponses acceptées oui/o/yes/y/non/n/no **plus** celles de la langue courante (ja/j/nein, sí/si/s, sì/si/s… via clés `ui.bool.yes`/`ui.bool.no` = liste séparée par des virgules) ; libellé `(oui/non)` traduit (`ui.bool.hint`).
- [ ] Adapter les tests qui vérifient des textes (ils tournent en anglais).
- [ ] `mvn -B -q test`, CRLF, commit « i18n : messages du moteur, de la connexion et des menus ».

### Task 3: Externaliser la CLI (paquet cli, sauf descriptions d'aide)

**Files:** `cli/*.java` (InitCommand, ConfigCommands, RightCommands, RightReports, RightsCommand, SynoRightCommand messages d'exécution) ; bundles EN/FR ; tests cli.

- [ ] Même méthode que Task 2 (clés `cli.*`). Les alignements de `config` (« Fichier        : ») : clés des libellés seuls, alignement calculé par le code (`String.format("%-15s: %s", …)`).
- [ ] Les descriptions picocli des annotations restent pour Task 6.
- [ ] Tests, CRLF, commit « i18n : messages de la console ».

### Task 4: Externaliser l'IHM + choix de la langue

**Files:** `gui/*.java` ; `ConfigForm`, `ConfigWindow` (liste de langue) ; bundles EN/FR ; tests gui.

- [ ] Clés `gui.*` pour tous les textes Swing (titres, libellés, boutons, dialogues, messages de validation de `ConfigForm` — les constantes `CONNECTION_REQUIRED` etc. deviennent des clés, `ConfigWindow.save` compare les clés/positions et plus les textes).
- [ ] `ConfigForm.language` (code `auto|en|fr|de|es|it`), inclus dans l'état (modification détectée) et dans `toPreferences`. `ConfigWindow` : `JComboBox` « Langue » (libellés : `gui.config.language.auto` traduit, puis noms natifs « English », « Français », « Deutsch », « Español », « Italiano », non traduits) dans la barre du bas ou la section Connexion ; après enregistrement d'une langue différente : message `gui.config.language.restart` (« La langue sera appliquée à la prochaine ouverture. »).
- [ ] Tests ConfigForm (langue modifiée → dirty, toPreferences) ; CRLF ; commit « i18n : interface graphique et choix de la langue ».

### Task 5: Menus de l'Explorateur traduits (libellés, titre racine, DLL)

**Files:** `menu/MenuEntry.java` (label → clé `menu.entry.<id>`), `menu/MenuDefinition.java` (titres « Ajouter X »/« Retirer X », `ROOT_TITLE` → `Messages.get("menu.root")`), `ClassicMenuInstaller` (MUIVerb), `ModernMenuInstaller` (valeur `RootTitle`), `native/synoRightMenu/synoRightMenu.cpp` (GetTitle lit `RootTitle`, repli `L"Synology Photos"`), tests menu.

- [ ] Clés : `menu.root` (en « Synology Photos permissions », fr « Droits Synology Photos »), `menu.entry.rights/open/status/push/pull/groups/config`, `menu.group.add` (« Add {0} » / « Ajouter {0} »), `menu.group.remove`.
- [ ] DLL : `GetTitle` lit `RootTitle` via `ReadString` sous try/catch, repli « Synology Photos » ; compiler (`msbuild` via vswhere, cf. `native/build.ps1`), 0 avertissement.
- [ ] Tests (anglais) : `MenuDefinitionTest`, `ClassicMenuInstallerTest`, `ModernMenuInstallerTest` (valeur `RootTitle`) ; un test en français (`Messages.setLanguage(FR)` puis restauration) sur les titres.
- [ ] CRLF (y compris .cpp), commit « i18n : menus de l'Explorateur traduits, titre racine lu par la DLL ».

### Task 6: Aide de la CLI traduite (picocli)

**Files:** `Main.java` (`commandLine.setResourceBundle(Messages.bundle())`), annotations `@Command`/`@Option`/`@Parameters` de `cli/*` et `gui/GuiCommand` (descriptions → clés picocli, voir doc picocli « Internationalization » : `usage.description`, `usage.headerHeading`…, clés d'options = nom d'option le plus long sans tirets ou `descriptionKey`, préfixées du nom de commande qualifié si ambigu), bundles EN/FR ; `MainTest`.

- [ ] Garder l'anglais dans les annotations n'est pas requis : la description vient du bundle ; vérifier que `--help` racine et `pull --help`, `menu install --help` affichent les textes du bundle.
- [ ] Sections standard picocli (« Usage: », « Commands: ») : traduire via les clés picocli `usage.synopsisHeading`, `usage.commandListHeading`, `usage.optionListHeading` si possible.
- [ ] Test `MainTest.langueDeLaConfiguration_avantLAide` : `--help` capturé (picocli `setOut` sur un `StringWriter` n'est pas accessible via `Main.run` : tester plutôt via une méthode `Main.newCommandLine()` extraite) en FR contient la description française de `pull`.
- [ ] CRLF, commit « i18n : aide de la console traduite ».

### Task 7: Allemand, espagnol, italien

**Files:** `messages_de.properties`, `messages_es.properties`, `messages_it.properties` (toutes les clés de `messages.properties`, mêmes paramètres), `MessagesTest` (DE/ES/IT inclus dans `memesClesEtParametres`).

- [ ] Traduire toutes les clés avec le glossaire ; registre « vous » formel (de : Sie ; es : usted ; it : Lei) ou tournures impersonnelles, comme le français actuel ; ponctuation propre à chaque langue (es : ¿…?).
- [ ] `mvn -B -q test` (cohérence), CRLF, commit « i18n : allemand, espagnol et italien ».

### Task 8: README, build et vérification

**Files:** `README.md` (anglais, traduction du contenu actuel + section langue), `README.fr.md` (contenu actuel français + section langue), liens croisés en tête ; build.

- [ ] Section « Language / Langue » : clé `language`, résolution auto, choix dans la fenêtre.
- [ ] `mvn -B test` (compter), `mvn -B -q package -Pexe -DskipTests` (sans `clean`) ; vérifier `java -Dsynoright.x -jar target/synoRight.jar --help` en EN, et en FR avec `-DsynoRight.language=fr` (accents corrects dans la sortie redirigée en UTF-8 : `-Dstdout.encoding=UTF-8`).
- [ ] CRLF, commit « i18n : README anglais et français ».
