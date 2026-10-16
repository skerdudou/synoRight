# Multilinguisme (anglais, français, allemand, espagnol, italien) — design

Date : 2026-10-16 — statut : validé en conversation (l'utilisateur ne relit pas, exécution autonome).

## Objectif

Tous les textes vus par l'utilisateur sont traduits en 5 langues : **anglais (référence et langue par défaut)**, français, allemand, espagnol, italien.

Ce que l'utilisateur a demandé / validé :
- anglais par défaut, + français, allemand, espagnol, italien ;
- langue choisie automatiquement (langue d'affichage de Windows si c'est l'une des 5, sinon anglais), modifiable dans la configuration ;
- périmètre : IHM (fenêtres, dialogues), entrées des menus de l'Explorateur, messages et erreurs de la console, aide de la CLI (`--help`), README ;
- toutes les traductions faites par Claude (pas de relecture native DE/ES/IT).

Hors périmètre : commentaires du code, Javadoc et messages de commit (restent en français, convention du dépôt) ; logs de debug (`LogUtil.debug`, temps de traitement « … time ») ; textes techniques non destinés à l'utilisateur (noms d'API, clés de registre, contenu .reg).

## Architecture

### Fichiers de traduction
- `src/main/resources/i18n/messages.properties` (anglais, référence et repli), `messages_fr.properties`, `messages_de.properties`, `messages_es.properties`, `messages_it.properties`.
- Encodage UTF-8 (lu nativement par `PropertyResourceBundle` depuis Java 9), fins de ligne CRLF.
- Clés hiérarchiques par domaine : `engine.*`, `cli.*`, `prefs.*`, `synology.*`, `menu.*`, `gui.*`, `ui.*`, `main.*`, plus les clés picocli (aide).
- Paramètres `{0}`, `{1}`… substitués par une méthode maison (pas `MessageFormat`) : l'apostrophe n'a aucun sens spécial, inutile de la doubler. `{{` n'est pas géré (inutile).

### API
```java
package sk.synoAdmin.i18n;
public enum Language { EN, FR, DE, ES, IT;  // code() = "en"...
	static Optional<Language> fromCode(String);
	/** "auto"/null/inconnu → langue d'affichage du système si supportée, sinon EN ; propriété système synoRight.language prioritaire. */
	static Language resolve(String setting); }
public final class Messages {
	static String get(String key, Object... args);  // clé absente de la langue → anglais → "!" + key + "!"
	static void setLanguage(Language); static Language language();
	static ResourceBundle bundle();                  // pour picocli
}
```
- `Locale.getDefault(Locale.Category.DISPLAY)` donne la langue d'affichage de Windows.
- Propriété système `synoRight.language` (tests, dépannage) prioritaire sur la configuration.

### Choix de la langue
- Clé de configuration `language` : `auto` (défaut), `en`, `fr`, `de`, `es`, `it` ; toujours écrite par `save()`, valeur inconnue lue comme `auto`.
- `Main.run` détermine la langue **avant** de construire la ligne de commande : répertoire de configuration (option `-c`/`--config` si présente dans les arguments, sinon résolution habituelle), lecture tolérante de la seule clé `language` (`Preferences.readLanguageSetting(Path)` : aucune erreur, aucune sortie, `auto` si fichier absent/illisible), puis `Messages.setLanguage(Language.resolve(...))`.
- Fenêtre de configuration : liste « Langue / Language » (Automatique, English, Français, Deutsch, Español, Italiano) ; enregistrée avec la configuration ; appliquée à la prochaine ouverture d'une fenêtre (message) et aux menus de l'Explorateur, réécrits à l'enregistrement (MenuService.refresh existant).
- CLI : pas de commande dédiée (la clé est éditable à la main ; la fenêtre la propose).

### Menus de l'Explorateur
- Libellés des entrées (`MenuEntry`, `MenuDefinition`) et titre racine traduits au moment de l'installation / du rafraîchissement.
- Menu classique : `MUIVerb` des clés racines = titre traduit.
- Menu Windows 11 : nouvelle valeur de registre `RootTitle` (REG_SZ) sous `HKCU\Software\synoRight\Menu` ; la DLL l'utilise pour `GetTitle`, repli « Synology Photos » si absente.

### Aide de la CLI
- picocli : `CommandLine.setResourceBundle(Messages.bundle())` sur la commande racine (hérité par les sous-commandes) ; descriptions de commandes et d'options via les clés picocli (`usage.description`, clés d'options), retirées des annotations ou gardées en repli anglais.

### Textes
- Anglais naturel et concis ; français = textes actuels ; DE/ES/IT traduits avec le glossaire :

| fr | en | de | es | it |
|---|---|---|---|---|
| droits | permissions | Berechtigungen | permisos | autorizzazioni |
| espace partagé | Shared Space | Gemeinsamer Bereich | Espacio compartido | Spazio condiviso |
| dossier photos | photo folder | Fotoordner | carpeta de fotos | cartella foto |
| synchronisation | synchronization | Synchronisierung | sincronización | sincronizzazione |
| groupe | group | Gruppe | grupo | gruppo |
| nom court | short name | Kurzname | nombre corto | nome breve |
| fichiers de droits | permission files | Berechtigungsdateien | archivos de permisos | file di autorizzazione |
| menu classique | classic menu | klassisches Menü | menú clásico | menu classico |
| Afficher plus d'options | Show more options | Weitere Optionen anzeigen | Mostrar más opciones | Mostra altre opzioni |

- « Synology Photos », « DSM », « NAS », « OTP » ne se traduisent pas.

### Tests
- Surefire : `synoRight.language=en` (tests en anglais) ; les assertions sur des textes français passent en anglais.
- `MessagesTest` : les 5 fichiers ont exactement les mêmes clés ; chaque traduction a les mêmes paramètres `{n}` que l'anglais ; repli anglais ; substitution sans traitement de l'apostrophe ; résolution `auto` et codes.

### README
- `README.md` en anglais (contenu actuel traduit), `README.fr.md` en français (contenu actuel), lien croisé en tête.
