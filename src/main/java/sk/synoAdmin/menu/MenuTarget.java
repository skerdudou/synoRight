package sk.synoAdmin.menu;

import java.util.Arrays;
import java.util.Optional;

/**
 * Menu(s) contextuel(s) à installer : Windows 11 (menu principal), classique (Afficher plus d'options), ou les deux.
 */
public enum MenuTarget {
	MODERN("modern"), CLASSIC("classic"), ALL("all");

	private final String id;

	MenuTarget(final String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public static Optional<MenuTarget> fromId(final String id) {
		return Arrays.stream(values()).filter(t -> t.id.equalsIgnoreCase(id.trim())).findAny();
	}
}
