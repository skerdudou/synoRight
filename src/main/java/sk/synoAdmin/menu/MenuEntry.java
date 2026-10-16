package sk.synoAdmin.menu;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Entrées proposables dans le menu contextuel de l'Explorateur, dans l'ordre d'affichage.
 */
public enum MenuEntry {
	RIGHTS("rights", "Droits du dossier…", true),
	OPEN("open", "Ouvrir dans Synology Photos", false),
	STATUS("status", "État de la synchronisation…", true),
	PUSH("push", "Synchroniser fichiers → Photos…", true),
	PULL("pull", "Synchroniser Photos → fichiers…", false),
	GROUPS("groups", "Ajouter / Retirer <groupe> (une entrée par groupe)", false),
	CONFIG("config", "Configuration de synoRight…", true);

	private final String id;
	private final String label;
	private final boolean defaultEnabled;

	MenuEntry(final String id, final String label, final boolean defaultEnabled) {
		this.id = id;
		this.label = label;
		this.defaultEnabled = defaultEnabled;
	}

	public String id() {
		return id;
	}

	/** Libellé dans le menu (pour GROUPS : description de l'option, les entrées réelles portent le nom du groupe). */
	public String label() {
		return label;
	}

	public boolean defaultEnabled() {
		return defaultEnabled;
	}

	public static List<MenuEntry> defaults() {
		return Arrays.stream(values()).filter(MenuEntry::defaultEnabled).toList();
	}

	public static Optional<MenuEntry> fromId(final String id) {
		return Arrays.stream(values()).filter(e -> e.id.equalsIgnoreCase(id.trim())).findAny();
	}
}
