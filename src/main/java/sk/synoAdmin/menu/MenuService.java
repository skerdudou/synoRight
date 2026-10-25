package sk.synoAdmin.menu;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import sk.synoAdmin.AppVersion;
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

		final String thumbprint;
		if (modern && classic) {
			// le menu classique tient « Afficher plus d'options » : l'entrée du package s'y masque
			thumbprint = installBoth(() -> ClassicMenuInstaller.install(classicItems, pref.getNasFolder(), pref.getConfigDir()),
					() -> ModernMenuInstaller.install(modernItems, pref.getConfigDir(), pref.getNasFolder(), true));
		} else if (modern) {
			// sans menu classique, l'entrée du package reste visible aussi dans « Afficher plus d'options »
			thumbprint = ModernMenuInstaller.install(modernItems, pref.getConfigDir(), pref.getNasFolder(), false);
			ClassicMenuInstaller.uninstall();
		} else {
			if (ModernMenuInstaller.isInstalled()) {
				ModernMenuInstaller.uninstall();
			}
			ClassicMenuInstaller.install(classicItems, pref.getNasFolder(), pref.getConfigDir());
			thumbprint = previousThumbprint();
		}
		writeMarker(pref, thumbprint, true);
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

	/**
	 * Installe le menu classique d'abord : un échec du menu Windows 11 le laisse en place et est signalé.
	 *
	 * @return l'empreinte du certificat renvoyée par l'installation du menu Windows 11
	 */
	static String installBoth(final Runnable classic, final Supplier<String> modern) {
		classic.run();
		try {
			return modern.get();
		} catch (final UserException e) {
			throw new UserException(Messages.get("menu.install.classicOnly", e.getMessage()), e);
		}
	}

	/** Désinstalle les deux menus. */
	public static void uninstall() {
		ModernMenuInstaller.uninstall();
		ClassicMenuInstaller.uninstall();
		deleteRootKey();
	}

	/** Supprime HKCU\Software\synoRight entière (menu, repère d'installation) si elle existe. */
	private static void deleteRootKey() {
		if (!ProcessRunner.isWindows()) {
			return;
		}
		if (ProcessRunner.run(List.of("reg", "query", InstallMarker.ROOT_KEY)).ok()) {
			final ProcessRunner.Result deleted = ProcessRunner.run(List.of("reg", "delete", InstallMarker.ROOT_KEY, "/f"));
			if (!deleted.ok()) {
				throw new UserException(Messages.get("menu.install.modern.deleteConfigFailed", deleted.output()));
			}
		}
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
		final boolean modern = ModernMenuInstaller.isInstalled();
		if (modern) {
			ModernMenuInstaller.refreshEntries(items(pref, true), pref.getConfigDir(), pref.getNasFolder(), classic);
		}
		if (classic || modern) {
			// empreinte gardée du repère précédent
			writeMarker(pref, null, false);
		}
	}

	/** Empreinte du certificat notée dans le repère actuel (null si aucune). */
	private static String previousThumbprint() {
		return InstallMarker.read().map(InstallMarker::certThumbprint).orElse(null);
	}

	/** Écrit le repère d'installation (voir {@link #markerToWrite}). */
	private static void writeMarker(final Preferences pref, final String thumbprint, final boolean install) {
		// repère précédent lu seulement au rafraîchissement
		markerToWrite(install ? Optional.empty() : InstallMarker.read(), AppVersion.current().orElse(null), CommandLines.join(Launchers.gui()), pref.getConfigDir().toString(),
				thumbprint, install).ifPresent(InstallMarker::write);
	}

	/**
	 * Repère à écrire. Installation : repère neuf pour l'application courante, sans tentative en cours. Rafraîchissement : le
	 * package Windows 11 n'est pas réinstallé, la version, le lanceur, l'empreinte et la tentative du repère précédent sont
	 * gardés (une réparation reste nécessaire après une mise à jour ou un déplacement), seul le dossier de configuration change ;
	 * sans repère précédent, rien n'est écrit.
	 */
	static Optional<InstallMarker> markerToWrite(final Optional<InstallMarker> previous, final String version, final String launcher,
			final String configDir, final String thumbprint, final boolean install) {
		if (install) {
			return Optional.of(new InstallMarker(version, launcher, configDir, thumbprint, null, null));
		}
		// thumbprint ignoré au rafraîchissement : le certificat n'est pas réinstallé
		return previous.map(p -> new InstallMarker(p.version(), p.launcher(), configDir, p.certThumbprint(), p.attemptedVersion(),
				p.attemptedLauncher()));
	}
}
