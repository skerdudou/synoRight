package sk.synoAdmin.menu;

import java.util.List;

import sk.synoAdmin.UserException;
import sk.synoAdmin.i18n.Messages;
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
		final List<MenuItem> modernItems = items(pref, true);
		final List<MenuItem> classicItems = items(pref, false);
		final boolean modern = target != MenuTarget.CLASSIC;
		final boolean classic = target != MenuTarget.MODERN;

		if (modern && classic) {
			// le menu classique tient « Afficher plus d'options » : l'entrée du package s'y masque
			installBoth(() -> ClassicMenuInstaller.install(classicItems, pref.getNasFolder(), pref.getConfigDir()),
					() -> ModernMenuInstaller.install(modernItems, pref.getConfigDir(), pref.getNasFolder(), true));
		} else if (modern) {
			// sans menu classique, l'entrée du package reste visible aussi dans « Afficher plus d'options »
			ModernMenuInstaller.install(modernItems, pref.getConfigDir(), pref.getNasFolder(), false);
			ClassicMenuInstaller.uninstall();
		} else {
			if (ModernMenuInstaller.isInstalled()) {
				ModernMenuInstaller.uninstall();
			}
			ClassicMenuInstaller.install(classicItems, pref.getNasFolder(), pref.getConfigDir());
		}
		final String detail = modern && classic ? Messages.get("menu.install.detail.both", modernItems.size(), classicItems.size())
				: modern ? Messages.get("menu.install.detail.modern", modernItems.size())
						: Messages.get("menu.install.detail.classic", classicItems.size());
		return Messages.get("menu.install.done", detail)
				// l'Explorateur garde parfois en cache l'état précédent du package
				+ (modern ? "\n" + Messages.get("menu.install.restartExplorer") : "");
	}

	/** Entrées du menu Windows 11 (modern) ou du menu classique, selon la configuration. */
	private static List<MenuItem> items(final Preferences pref, final boolean modern) {
		return MenuDefinition.build(pref.getMenuEntries(modern), pref.getGroups());
	}

	/** Installe le menu classique d'abord : un échec du menu Windows 11 le laisse en place et est signalé. */
	static void installBoth(final Runnable classic, final Runnable modern) {
		classic.run();
		try {
			modern.run();
		} catch (final UserException e) {
			throw new UserException(Messages.get("menu.install.classicOnly", e.getMessage()), e);
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
		final boolean classic = ClassicMenuInstaller.isInstalled();
		if (classic) {
			ClassicMenuInstaller.install(items(pref, false), pref.getNasFolder(), pref.getConfigDir());
		}
		if (ModernMenuInstaller.isInstalled()) {
			ModernMenuInstaller.refreshEntries(items(pref, true), pref.getConfigDir(), pref.getNasFolder(), classic);
		}
	}
}
