package sk.synoAdmin.menu;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import sk.synoAdmin.i18n.Messages;

/**
 * Entrées proposables dans le menu contextuel de l'Explorateur, dans l'ordre d'affichage.
 */
public enum MenuEntry {
	RIGHTS("rights", true),
	OPEN("open", false),
	STATUS("status", true),
	PUSH("push", true),
	PULL("pull", false),
	GROUPS("groups", false),
	CONFIG("config", true);

	private final String id;
	private final boolean defaultEnabled;

	MenuEntry(final String id, final boolean defaultEnabled) {
		this.id = id;
		this.defaultEnabled = defaultEnabled;
	}

	public String id() {
		return id;
	}

	/** Libellé dans le menu, dans la langue courante (pour GROUPS : description de l'option, les entrées réelles portent le nom du groupe). */
	public String label() {
		return Messages.get("menu.entry." + id);
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
