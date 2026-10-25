package sk.synoAdmin.menu;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sk.synoAdmin.AppVersion;
import sk.synoAdmin.LogUtil;
import sk.synoAdmin.prefs.Preferences;

/**
 * Réparation des menus installés après une mise à jour de l'application : le repère d'installation indique la version
 * et le lanceur utilisés par les menus ; s'ils diffèrent de l'application courante, les menus présents sont réinstallés.
 */
public final class MenuRepair {

	/** Verrou : le menu classique lance plusieurs processus à la fois, un seul répare. */
	private static final Path LOCK_FILE = Path.of(System.getProperty("java.io.tmpdir"), "synoRight-repair.lock");

	// ligne de valeur de « reg query » : « Nom    REG_SZ    valeur » (nom localisé : « (Default) », « (Par défaut) »…)
	private static final Pattern VALUE_LINE = Pattern.compile("^\\s+.*?\\s+REG_SZ\\s+(.*?)\\s*$");

	/**
	 * @param classic clé du menu classique présente
	 * @param modernConfig configuration du menu Windows 11 (HKCU\Software\synoRight\Menu) présente
	 */
	public record InstalledMenus(boolean classic, boolean modernConfig) {

		boolean any() {
			return classic || modernConfig;
		}

		/** Lecture par « reg query » (rapide, pas de PowerShell). */
		static InstalledMenus read() {
			return new InstalledMenus(ClassicMenuInstaller.isInstalled(), ProcessRunner.isWindows()
					&& ProcessRunner.run(List.of("reg", "query", ModernMenuInstaller.MENU_KEY)).ok());
		}
	}

	/** NOTHING : rien à faire ; REPAIRED : menus réinstallés ; BUSY : réparation en cours dans un autre processus. */
	public enum Outcome { NOTHING, REPAIRED, BUSY }

	/**
	 * FORCED : « menu repair » (répare même si le repère est à jour, tentative notée) ; STARTUP : démarrage de l'IHM (tentative
	 * notée : un échec n'est pas retenté à chaque lancement) ; IF_NEEDED : installateur (« menu repair --if-needed », exécuté
	 * caché, code de sortie ignoré : la tentative n'est pas notée, l'IHM relancée retente et affiche l'erreur).
	 */
	public enum Mode {
		FORCED(true, true), STARTUP(false, true), IF_NEEDED(false, false);

		private final boolean force;
		private final boolean recordAttempt;

		Mode(final boolean force, final boolean recordAttempt) {
			this.force = force;
			this.recordAttempt = recordAttempt;
		}

		/** Répare même si le repère est à jour ou si la réparation a déjà été tentée. */
		public boolean force() {
			return force;
		}

		/** Note la tentative dans le repère avant la réinstallation. */
		public boolean recordAttempt() {
			return recordAttempt;
		}
	}

	private MenuRepair() {
		// utility
	}

	/**
	 * Réparation nécessaire si un menu est installé et que le repère est absent, ou que sa version ou son lanceur diffère
	 * (sauf si une réparation a déjà été tentée pour cette version et ce lanceur). Sans exe jpackage (jar), la réparation
	 * automatique est ignorée si le menu Windows 11 est configuré : le package ne peut pas être installé depuis le jar, les
	 * menus ne sont pas repointés vers cette copie.
	 *
	 * @param force ignore la tentative précédente, le repère à jour et l'absence d'exe
	 * @param appInstalled application lancée par son exe jpackage ({@link Launchers#appDir()} présent)
	 */
	static boolean needsRepair(final Optional<InstallMarker> marker, final InstalledMenus menus, final String version, final String launcher,
			final boolean force, final boolean appInstalled) {
		if (!menus.any()) {
			return false;
		}
		return force || pending(marker, version, launcher) && (appInstalled || !menus.modernConfig());
	}

	/** Repère absent, ou différent de l'application courante sans tentative pour elle : réparation à faire si un menu est installé. */
	private static boolean pending(final Optional<InstallMarker> marker, final String version, final String launcher) {
		if (marker.isEmpty()) {
			return true;
		}
		final InstallMarker installed = marker.get();
		final boolean outdated = !Objects.equals(version, installed.version()) || !Objects.equals(launcher, installed.launcher());
		final boolean attempted = Objects.equals(version, installed.attemptedVersion()) && Objects.equals(launcher, installed.attemptedLauncher());
		return outdated && !attempted;
	}

	/** Dossier de configuration des menus : repère, sinon configuration du menu Windows 11, sinon argument -c du menu classique. */
	static Path configDir(final Optional<InstallMarker> marker, final Optional<String> modernConfigDir, final Optional<String> classicCommand) {
		return marker.map(InstallMarker::configDir)
				.or(() -> modernConfigDir)
				.or(() -> classicCommand.flatMap(MenuRepair::configArg))
				.map(Path::of)
				.orElseGet(() -> Preferences.resolveConfigDir(null));
	}

	private static Optional<String> configArg(final String command) {
		final List<String> args = CommandLines.split(command);
		final int index = args.indexOf("-c");
		return index >= 0 && index + 1 < args.size() ? Optional.of(args.get(index + 1)) : Optional.empty();
	}

	/** Cible déduite des menus présents (pas de la configuration : un menu retiré n'est pas réinstallé). */
	static MenuTarget target(final InstalledMenus menus) {
		if (menus.classic() && menus.modernConfig()) {
			return MenuTarget.ALL;
		}
		return menus.modernConfig() ? MenuTarget.MODERN : MenuTarget.CLASSIC;
	}

	/** Première commande de la sortie de « reg query SUBMENU_KEY\shell /s » (valeur par défaut d'une clé \command). */
	static Optional<String> parseClassicCommand(final String regQueryOutput) {
		boolean inCommand = false;
		for (final String line : regQueryOutput.split("\\r?\\n")) {
			if (line.startsWith("HKEY_")) {
				inCommand = line.strip().endsWith("\\command");
				continue;
			}
			final Matcher matcher = VALUE_LINE.matcher(line);
			if (inCommand && matcher.matches()) {
				return Optional.of(matcher.group(1));
			}
		}
		return Optional.empty();
	}

	/** Verrou exclusif sur le fichier ; vide s'il est déjà tenu (par ce processus ou un autre). Fermer son canal le libère. */
	static Optional<FileLock> tryLock(final Path file) {
		FileChannel channel = null;
		try {
			channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			final FileLock lock = channel.tryLock();
			if (lock != null) {
				return Optional.of(lock);
			}
		} catch (final OverlappingFileLockException e) {
			// déjà tenu dans ce processus
		} catch (final IOException e) {
			LogUtil.debug("Verrou " + file + " non obtenu : " + e.getMessage());
		}
		close(channel);
		return Optional.empty();
	}

	private static void close(final FileChannel channel) {
		if (channel == null) {
			return;
		}
		try {
			channel.close();
		} catch (final IOException e) {
			LogUtil.debug("Fermeture du verrou : " + e.getMessage());
		}
	}

	/** Réparation nécessaire pour l'application courante (faux en développement). */
	public static boolean isNeeded() {
		final Optional<String> version = AppVersion.current();
		if (version.isEmpty()) {
			return false;
		}
		final Optional<InstallMarker> marker = InstallMarker.read();
		final String launcher = CommandLines.join(Launchers.gui());
		// repère à jour (cas courant) : un seul reg query, sans chercher les menus installés
		return pending(marker, version.get(), launcher)
				&& needsRepair(marker, InstalledMenus.read(), version.get(), launcher, false, Launchers.appDir().isPresent());
	}

	/**
	 * Réinstalle les menus présents si nécessaire. Selon le mode, la tentative est notée dans le repère avant la
	 * réinstallation : un échec n'est alors pas retenté à chaque lancement (sauf FORCED) ; l'exception remonte à l'appelant.
	 */
	public static Outcome repair(final Mode mode) {
		final boolean force = mode.force();
		final Optional<String> version = AppVersion.current();
		if (version.isEmpty()) {
			return Outcome.NOTHING;
		}
		final Optional<FileLock> lock = tryLock(LOCK_FILE);
		if (lock.isEmpty()) {
			return Outcome.BUSY;
		}
		try {
			final Optional<InstallMarker> marker = InstallMarker.read();
			final String launcher = CommandLines.join(Launchers.gui());
			// repère à jour (cas courant) : un seul reg query, sans chercher les menus installés
			if (!force && !pending(marker, version.get(), launcher)) {
				return Outcome.NOTHING;
			}
			final InstalledMenus menus = InstalledMenus.read();
			if (!needsRepair(marker, menus, version.get(), launcher, force, Launchers.appDir().isPresent())) {
				return Outcome.NOTHING;
			}
			if (mode.recordAttempt()) {
				marker.orElse(new InstallMarker(null, null, null, null, null, null)).withAttempt(version.get(), launcher).write();
			}
			// sources de secours lues seulement sans dossier dans le repère
			final Path configDir = marker.map(InstallMarker::configDir).map(Path::of)
					.orElseGet(() -> configDir(marker, modernConfigDir(), classicCommand()));
			final Preferences pref = Preferences.fromFile(configDir);
			MenuService.install(pref, target(menus));
			return Outcome.REPAIRED;
		} finally {
			close(lock.get().channel());
		}
	}

	/** Valeur ConfigDir de la configuration du menu Windows 11. */
	private static Optional<String> modernConfigDir() {
		final ProcessRunner.Result result = ProcessRunner.run(List.of("reg", "query", ModernMenuInstaller.MENU_KEY, "/v", "ConfigDir"));
		if (!result.ok()) {
			return Optional.empty();
		}
		final Matcher matcher = Pattern.compile("ConfigDir\\s+REG_SZ\\s+(.*?)\\s*$", Pattern.MULTILINE).matcher(result.output());
		return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
	}

	/** Commande de la première entrée du menu classique. */
	private static Optional<String> classicCommand() {
		final ProcessRunner.Result result = ProcessRunner.run(List.of("reg", "query", ClassicMenuInstaller.SUBMENU_KEY + "\\shell", "/s"));
		return result.ok() ? parseClassicCommand(result.output()) : Optional.empty();
	}
}
