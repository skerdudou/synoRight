package sk.synoAdmin.menu;

import java.util.ArrayList;
import java.util.List;

import sk.synoAdmin.engine.Group;
import sk.synoAdmin.i18n.Messages;

/**
 * Entrées du menu contextuel, communes au menu classique et au menu Windows 11.
 * Les arguments sont ceux de la commande 'gui' ; PATH est remplacé par le dossier cliqué.
 */
public final class MenuDefinition {

	public static final String PATH = "%V";

	/** Titre du sous-menu racine, dans la langue courante. */
	public static String rootTitle() {
		return Messages.get("menu.root");
	}

	/**
	 * @param key nom de clé de registre (ordre d'affichage)
	 * @param separatorBefore séparateur avant l'entrée (première entrée d'un bloc)
	 */
	public record MenuItem(String key, String title, List<String> args, boolean separatorBefore) {
	}

	private MenuDefinition() {
		// utility
	}

	public static List<MenuItem> build(final List<MenuEntry> entries, final List<Group> groups) {
		final List<MenuItem> items = new ArrayList<>();
		// blocs : dossier / synchro / groupes / configuration
		addBlock(items, entries, groups, MenuEntry.RIGHTS, MenuEntry.OPEN);
		addBlock(items, entries, groups, MenuEntry.STATUS, MenuEntry.PUSH, MenuEntry.PULL);
		addBlock(items, entries, groups, MenuEntry.GROUPS);
		addBlock(items, entries, groups, MenuEntry.CONFIG);
		return items;
	}

	private static void addBlock(final List<MenuItem> items, final List<MenuEntry> entries, final List<Group> groups, final MenuEntry... block) {
		boolean first = true;
		for (final MenuEntry entry : block) {
			if (!entries.contains(entry)) {
				continue;
			}
			for (final String[] item : itemsOf(entry, groups)) {
				final boolean separator = first && !items.isEmpty();
				final String key = String.format("%02d%s", items.size() + 1, item[0]);
				items.add(new MenuItem(key, item[1], List.of(item).subList(2, item.length), separator));
				first = false;
			}
		}
	}

	// {suffixe de clé, titre, arguments...}
	private static List<String[]> itemsOf(final MenuEntry entry, final List<Group> groups) {
		return switch (entry) {
			case RIGHTS -> List.<String[]>of(new String[] { "rights", entry.label(), "rights", PATH });
			case OPEN -> List.<String[]>of(new String[] { "open", entry.label(), "open", PATH });
			case STATUS -> List.<String[]>of(new String[] { "status", entry.label(), "sync" });
			case PUSH -> List.<String[]>of(new String[] { "push", entry.label(), "sync", "--push" });
			case PULL -> List.<String[]>of(new String[] { "pull", entry.label(), "sync", "--pull" });
			case CONFIG -> List.<String[]>of(new String[] { "config", entry.label(), "config" });
			case GROUPS -> groups.stream()
					.<String[]>mapMulti((g, out) -> {
						out.accept(new String[] { "add" + g.getId(), Messages.get("menu.group.add", g.getName()), "grant", g.getName(), PATH });
						out.accept(new String[] { "del" + g.getId(), Messages.get("menu.group.remove", g.getName()), "revoke", g.getName(), PATH });
					})
					.toList();
		};
	}
}
