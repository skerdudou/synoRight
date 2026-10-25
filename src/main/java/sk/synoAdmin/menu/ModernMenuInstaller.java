package sk.synoAdmin.menu;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import sk.synoAdmin.AppVersion;
import sk.synoAdmin.ConsoleUtil;
import sk.synoAdmin.LogUtil;
import sk.synoAdmin.UserException;
import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.menu.MenuDefinition.MenuItem;

/**
 * Menu du nouveau menu contextuel de Windows 11 : DLL synoRightMenu-&lt;version&gt;.dll déclarée par le
 * package sparse synoRight.msix (signé par un certificat auto-signé), configurée par
 * HKCU\Software\synoRight\Menu que la DLL relit à chaque clic droit.
 */
public final class ModernMenuInstaller {

	public static final String PACKAGE_NAME = "synoRight";
	static final String MENU_KEY = "HKEY_CURRENT_USER\\Software\\synoRight\\Menu";
	private static final String DLL_PREFIX = "synoRightMenu";
	static final String MSIX = "synoRight.msix";
	static final String CER = "synoRight.cer";
	private static final int WINDOWS_11_BUILD = 22000;

	/**
	 * @param available true si le menu Windows 11 peut être installé
	 * @param reason motif d'indisponibilité (null si disponible)
	 */
	public record Availability(boolean available, String reason) {
	}

	private ModernMenuInstaller() {
		// utility
	}

	/** Vérifie que le système et l'application permettent d'installer le menu Windows 11. */
	public static Availability availability() {
		if (!ProcessRunner.isWindows()) {
			return new Availability(false, Messages.get("menu.install.modern.windowsOnly"));
		}
		if (windowsBuild() < WINDOWS_11_BUILD) {
			return new Availability(false, Messages.get("menu.install.modern.needsWindows11"));
		}
		final Optional<Path> appDir = Launchers.appDir();
		if (appDir.isEmpty()) {
			return new Availability(false, Messages.get("menu.install.modern.needsApp"));
		}
		final Optional<String> version = AppVersion.current();
		if (version.isEmpty()) {
			return new Availability(false, Messages.get("menu.install.modern.needsApp"));
		}
		for (final String file : List.of(dllName(version.get()), MSIX, CER)) {
			if (!Files.isRegularFile(appDir.get().resolve(file))) {
				return new Availability(false, Messages.get("menu.install.modern.fileMissing", file, appDir.get()));
			}
		}
		return new Availability(true, null);
	}

	/** Nom de la DLL de la version donnée : le nom change à chaque version, l'ancienne DLL pouvant rester chargée par l'Explorateur. */
	static String dllName(final String version) {
		return DLL_PREFIX + "-" + version + ".dll";
	}

	/** Noms de DLL du menu (synoRightMenu*.dll, casse ignorée) autres que celle de la version donnée, ancien nom sans version compris. */
	static List<String> staleDlls(final List<String> fileNames, final String version) {
		final String current = dllName(version);
		return fileNames.stream()
				.filter(name -> name.toLowerCase(Locale.ROOT).startsWith(DLL_PREFIX.toLowerCase(Locale.ROOT))
						&& name.toLowerCase(Locale.ROOT).endsWith(".dll") && !name.equalsIgnoreCase(current))
				.toList();
	}

	/** Supprime les anciennes DLL du dossier de l'application ; celles encore chargées par l'Explorateur sont ignorées. */
	static void deleteStaleDlls(final Path appDir) {
		final Optional<String> version = AppVersion.current();
		if (version.isEmpty()) {
			return;
		}
		try (Stream<Path> files = Files.list(appDir)) {
			final List<String> names = files.map(file -> file.getFileName().toString()).toList();
			for (final String stale : staleDlls(names, version.get())) {
				try {
					Files.deleteIfExists(appDir.resolve(stale));
				} catch (final IOException e) {
					LogUtil.debug("DLL " + stale + " non supprimée : " + e.getMessage());
				}
			}
		} catch (final IOException e) {
			LogUtil.debug("Dossier " + appDir + " illisible : " + e.getMessage());
		}
	}

	private static int windowsBuild() {
		final ProcessRunner.Result result = ProcessRunner.run(List.of("reg", "query",
				"HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion", "/v", "CurrentBuildNumber"));
		final Matcher matcher = Pattern.compile("CurrentBuildNumber\\s+REG_SZ\\s+(\\d+)").matcher(result.output());
		return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
	}

	/**
	 * Approuve le certificat, installe (ou met à jour sur place) le package sparse puis écrit les entrées du menu.
	 *
	 * @param hideInLegacyMenu true si le menu classique est aussi installé : sous Windows 11, l'entrée du package
	 *            apparaît aussi dans « Afficher plus d'options », la DLL s'y masque pour ne pas doubler le menu classique
	 * @return l'empreinte du certificat approuvé
	 */
	public static String install(final List<MenuItem> items, final Path configDir, final String photoFolder, final boolean hideInLegacyMenu) {
		final Availability availability = availability();
		if (!availability.available()) {
			throw new UserException(Messages.get("menu.install.modern.unavailable", availability.reason()));
		}
		final Path appDir = Launchers.appDir().orElseThrow(ModernMenuInstaller::notInstalledApp);
		final String thumbprint = trustCertificate(appDir.resolve(CER));

		// même version, autre dossier ou version plus récente refusés par Add-AppxPackage : on retire l'ancien package ;
		// une version plus ancienne dans le même dossier est mise à jour sur place (le menu reste en place)
		final PackageAction action = packageAction(installedPackage(), appDir, packageVersion(AppVersion.current().orElseThrow()));
		if (action == PackageAction.REINSTALL) {
			removePackage();
		}
		ProcessRunner.Result added = addPackage(appDir);
		if (!added.ok() && action == PackageAction.UPGRADE) {
			// mise à jour sur place refusée (package utilisé, par exemple par dllhost.exe) : retrait puis ajout
			LogUtil.debug("Mise à jour du package refusée, réinstallation : " + added.output());
			removePackage();
			added = addPackage(appDir);
		}
		if (!added.ok()) {
			throw new UserException(Messages.get("menu.install.modern.addPackageFailed", added.output()));
		}
		refreshEntries(items, configDir, photoFolder, hideInLegacyMenu);
		deleteStaleDlls(appDir);
		return thumbprint;
	}

	private static ProcessRunner.Result addPackage(final Path appDir) {
		return powershell("Add-AppxPackage -Path " + psQuote(appDir.resolve(MSIX).toString()) + " -ExternalLocation " + psQuote(appDir.toString()));
	}

	/**
	 * @param version version du package installé (a.b.c.d)
	 * @param installLocation dossier externe du package sparse (le dossier de l'application)
	 */
	record InstalledPackage(String version, String installLocation) {
	}

	/** ADD : pas de package ; UPGRADE : mise à jour sur place ; REINSTALL : retrait puis ajout. */
	enum PackageAction { ADD, UPGRADE, REINSTALL }

	static PackageAction packageAction(final Optional<InstalledPackage> installed, final Path appDir, final String packageVersion) {
		if (installed.isEmpty()) {
			return PackageAction.ADD;
		}
		final boolean sameDir = Path.of(installed.get().installLocation()).normalize().toString()
				.equalsIgnoreCase(appDir.normalize().toString());
		return sameDir && AppVersion.compare(installed.get().version(), packageVersion) < 0 ? PackageAction.UPGRADE : PackageAction.REINSTALL;
	}

	/** Version de package (a.b.c.d) d'une version Maven : 0.4-SNAPSHOT devient 0.4.0.0, comme dans build.ps1. */
	static String packageVersion(final String appVersion) {
		final List<String> parts = new ArrayList<>(List.of(appVersion.replaceFirst("-.*$", "").split("\\.")));
		while (parts.size() < 4) {
			parts.add("0");
		}
		return String.join(".", parts);
	}

	/** Package synoRight installé pour l'utilisateur, absent si aucun ou si la lecture échoue. */
	static Optional<InstalledPackage> installedPackage() {
		if (!ProcessRunner.isWindows()) {
			return Optional.empty();
		}
		final ProcessRunner.Result result = powershell("Get-AppxPackage -Name " + PACKAGE_NAME
				+ " | ForEach-Object { $_.Version; $_.InstallLocation }");
		if (!result.ok()) {
			return Optional.empty();
		}
		final List<String> lines = result.output().lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
		return lines.size() >= 2 ? Optional.of(new InstalledPackage(lines.get(0), lines.get(1))) : Optional.empty();
	}

	private static UserException notInstalledApp() {
		return new UserException(Messages.get("menu.install.modern.notInstalledApp"));
	}

	/** Réécrit les entrées du menu dans le registre, sans réinstaller le package (voir install pour hideInLegacyMenu). */
	public static void refreshEntries(final List<MenuItem> items, final Path configDir, final String photoFolder, final boolean hideInLegacyMenu) {
		final String launcher = Launchers.appDir().orElseThrow(ModernMenuInstaller::notInstalledApp).resolve(Launchers.GUI_EXE).toString();
		ClassicMenuInstaller.importRegFile(buildRegFileContent(items, launcher, configDir, photoFolder, hideInLegacyMenu));
	}

	/** Retire le package et la configuration du menu. */
	public static void uninstall() {
		if (!ProcessRunner.isWindows()) {
			return;
		}
		if (isInstalled()) {
			removePackage();
		}
		// clé absente = déjà désinstallé : on ne supprime que si elle existe (le repère d'installation est géré par MenuService)
		if (ProcessRunner.run(List.of("reg", "query", MENU_KEY)).ok()) {
			final ProcessRunner.Result deleted = ProcessRunner.run(List.of("reg", "delete", MENU_KEY, "/f"));
			if (!deleted.ok()) {
				throw new UserException(Messages.get("menu.install.modern.deleteConfigFailed", deleted.output()));
			}
		}
	}

	private static void removePackage() {
		final ProcessRunner.Result removed = powershell("Get-AppxPackage -Name " + PACKAGE_NAME + " | Remove-AppxPackage");
		if (!removed.ok()) {
			throw new UserException(Messages.get("menu.install.modern.removePackageFailed", removed.output()));
		}
	}

	public static boolean isInstalled() {
		return ProcessRunner.isWindows()
				&& powershell("if (Get-AppxPackage -Name " + PACKAGE_NAME + ") { 'installe' }").output().contains("installe");
	}

	static String buildRegFileContent(final List<MenuItem> items, final String launcherExe, final Path configDir, final String photoFolder,
			final boolean hideInLegacyMenu) {
		final StringBuilder reg = new StringBuilder("Windows Registry Editor Version 5.00\r\n");
		// suppression préalable : entrées obsolètes
		reg.append("\r\n[-").append(MENU_KEY).append("]\r\n");
		reg.append("\r\n[").append(MENU_KEY).append("]\r\n")
				.append("\"Launcher\"=\"").append(escape(launcherExe)).append("\"\r\n")
				.append("\"ConfigDir\"=\"").append(escape(configDir.toString())).append("\"\r\n")
				.append("\"PhotoFolder\"=\"").append(escape(photoFolder)).append("\"\r\n")
				.append("\"HideInLegacyMenu\"=dword:0000000").append(hideInLegacyMenu ? "1" : "0").append("\r\n")
					.append("\"RootTitle\"=\"").append(escape(MenuDefinition.rootTitle())).append("\"\r\n");
		int index = 1;
		for (final MenuItem item : items) {
			final List<String> args = new ArrayList<>(List.of("-c", configDir.toString()));
			args.addAll(item.args());
			reg.append("\r\n[").append(MENU_KEY).append("\\Entries\\").append(String.format("%02d", index++)).append("]\r\n")
					.append("\"Title\"=\"").append(escape(item.title())).append("\"\r\n")
					.append("\"Args\"=\"").append(escape(CommandLines.join(args))).append("\"\r\n")
					.append("\"SeparatorBefore\"=dword:0000000").append(item.separatorBefore() ? "1" : "0").append("\r\n");
		}
		return reg.toString();
	}

	// échappement du format .reg : backslashs et guillemets
	private static String escape(final String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	/** Chaîne PowerShell entre apostrophes. */
	static String psQuote(final String value) {
		return "'" + value.replace("'", "''") + "'";
	}

	private static ProcessRunner.Result powershell(final String command) {
		return ProcessRunner.run(List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", command));
	}

	/** Approuve le certificat du package (magasin machine TrustedPeople) : une élévation UAC la première fois. Retourne son empreinte. */
	private static String trustCertificate(final Path cer) {
		final String thumbprint = thumbprint(cer);
		final String check = "Test-Path " + psQuote("Cert:\\LocalMachine\\TrustedPeople\\" + thumbprint);
		if (powershell(check).output().contains("True")) {
			return thumbprint;
		}
		ConsoleUtil.info(Messages.get("menu.install.modern.adminPrompt"));
		final String importCommand = "Import-Certificate -FilePath " + psQuote(cer.toString()) + " -CertStoreLocation Cert:\\LocalMachine\\TrustedPeople";
		powershell("Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList '-NoProfile','-Command',"
				+ psQuote(importCommand));
		if (!powershell(check).output().contains("True")) {
			throw new UserException(Messages.get("menu.install.modern.certNotTrusted"));
		}
		return thumbprint;
	}

	// empreinte SHA-1 (thumbprint Windows) du certificat
	static String thumbprint(final Path cer) {
		try (InputStream in = Files.newInputStream(cer)) {
			final Certificate certificate = CertificateFactory.getInstance("X.509").generateCertificate(in);
			return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1").digest(certificate.getEncoded()));
		} catch (final IOException | GeneralSecurityException e) {
			throw new UserException(Messages.get("menu.install.modern.certUnreadable", cer), e);
		}
	}
}
