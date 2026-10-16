package sk.synoAdmin.menu;

import java.util.List;

import sk.synoAdmin.UserException;
import sk.synoAdmin.menu.MenuDefinition.MenuItem;
import sk.synoAdmin.prefs.Preferences;

/**
 * Installation des menus contextuels selon la configuration (entrées, cible).
 */
public final class MenuService {

	/**
	 * @param classic menu classique installé
	 * @param modern menu Windows 11 installé
	 */
	public record Status(boolean classic, boolean modern) {

		public boolean any() {
			return classic || modern;
		}
	}

	private MenuService() {
		// utility
	}

	/** Cible configurée, sinon Windows 11 si disponible, sinon classique. */
	public static MenuTarget effectiveTarget(final Preferences pref) {
		if (pref.getMenuTarget() != null) {
			return pref.getMenuTarget();
		}
		return ModernMenuInstaller.availability().available() ? MenuTarget.MODERN : MenuTarget.CLASSIC;
	}

	/** Installe le(s) menu(s) de la cible et retire l'autre ; renvoie un message de résultat. */
	public static String install(final Preferences pref, final MenuTarget target) {
		final List<MenuItem> items = MenuDefinition.build(pref.getMenuEntries(), pref.getGroups());
		final boolean modern = target != MenuTarget.CLASSIC;
		final boolean classic = target != MenuTarget.MODERN;

		if (modern && classic) {
			installBoth(() -> ClassicMenuInstaller.install(items, pref.getNasFolder(), pref.getConfigDir()),
					() -> ModernMenuInstaller.install(items, pref.getConfigDir(), pref.getNasFolder()));
		} else if (modern) {
			ModernMenuInstaller.install(items, pref.getConfigDir(), pref.getNasFolder());
			ClassicMenuInstaller.uninstall();
		} else {
			if (ModernMenuInstaller.isInstalled()) {
				ModernMenuInstaller.uninstall();
			}
			ClassicMenuInstaller.install(items, pref.getNasFolder(), pref.getConfigDir());
		}
		return "Menu contextuel installé pour l'utilisateur courant ("
				+ (modern && classic ? "Windows 11 et classique" : modern ? "Windows 11" : "classique") + ", "
				+ items.size() + " entrée(s)).";
	}

	/** Installe le menu classique d'abord : un échec du menu Windows 11 le laisse en place et est signalé. */
	static void installBoth(final Runnable classic, final Runnable modern) {
		classic.run();
		try {
			modern.run();
		} catch (final UserException e) {
			throw new UserException("Menu classique installé, mais menu Windows 11 non installé : " + e.getMessage(), e);
		}
	}

	/** Désinstalle les deux menus. */
	public static void uninstall() {
		ModernMenuInstaller.uninstall();
		ClassicMenuInstaller.uninstall();
	}

	public static Status status() {
		return new Status(ClassicMenuInstaller.isInstalled(), ModernMenuInstaller.isInstalled());
	}

	/** Met à jour les menus installés après un changement de groupes ou d'entrées (sans réinstaller le package). */
	public static void refresh(final Preferences pref) {
		final List<MenuItem> items = MenuDefinition.build(pref.getMenuEntries(), pref.getGroups());
		if (ClassicMenuInstaller.isInstalled()) {
			ClassicMenuInstaller.install(items, pref.getNasFolder(), pref.getConfigDir());
		}
		if (ModernMenuInstaller.isInstalled()) {
			ModernMenuInstaller.refreshEntries(items, pref.getConfigDir(), pref.getNasFolder());
		}
	}
}
