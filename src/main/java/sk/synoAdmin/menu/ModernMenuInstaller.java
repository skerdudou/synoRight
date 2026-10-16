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
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sk.synoAdmin.ConsoleUtil;
import sk.synoAdmin.UserException;
import sk.synoAdmin.menu.MenuDefinition.MenuItem;

/**
 * Menu du nouveau menu contextuel de Windows 11 : DLL synoRightMenu.dll déclarée par le
 * package sparse synoRight.msix (signé par un certificat auto-signé), configurée par
 * HKCU\Software\synoRight\Menu que la DLL relit à chaque clic droit.
 */
public final class ModernMenuInstaller {

	public static final String PACKAGE_NAME = "synoRight";
	static final String MENU_KEY = "HKEY_CURRENT_USER\\Software\\synoRight\\Menu";
	static final String DLL = "synoRightMenu.dll";
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
			return new Availability(false, "disponible seulement sous Windows 11");
		}
		if (windowsBuild() < WINDOWS_11_BUILD) {
			return new Availability(false, "nécessite Windows 11");
		}
		final Optional<Path> appDir = Launchers.appDir();
		if (appDir.isEmpty()) {
			return new Availability(false, "nécessite l'application installée (synoRightGui.exe), pas le jar seul");
		}
		for (final String file : List.of(DLL, MSIX, CER)) {
			if (!Files.isRegularFile(appDir.get().resolve(file))) {
				return new Availability(false, "fichier " + file + " absent de " + appDir.get() + " (application construite sans le menu Windows 11)");
			}
		}
		return new Availability(true, null);
	}

	private static int windowsBuild() {
		final ProcessRunner.Result result = ProcessRunner.run(List.of("reg", "query",
				"HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion", "/v", "CurrentBuildNumber"));
		final Matcher matcher = Pattern.compile("CurrentBuildNumber\\s+REG_SZ\\s+(\\d+)").matcher(result.output());
		return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
	}

	/** Approuve le certificat, installe le package sparse puis écrit les entrées du menu. */
	public static void install(final List<MenuItem> items, final Path configDir, final String photoFolder) {
		final Availability availability = availability();
		if (!availability.available()) {
			throw new UserException("Menu Windows 11 indisponible : " + availability.reason() + ".");
		}
		final Path appDir = Launchers.appDir().orElseThrow();
		trustCertificate(appDir.resolve(CER));

		// même version et contenu différent refusés par Add-AppxPackage : on retire l'ancien package
		if (isInstalled()) {
			powershell("Get-AppxPackage -Name " + PACKAGE_NAME + " | Remove-AppxPackage");
		}
		final ProcessRunner.Result added = powershell("Add-AppxPackage -Path " + psQuote(appDir.resolve(MSIX).toString())
				+ " -ExternalLocation " + psQuote(appDir.toString()));
		if (!added.ok()) {
			throw new UserException("Installation du package du menu Windows 11 en échec : " + added.output());
		}
		refreshEntries(items, configDir, photoFolder);
	}

	/** Réécrit les entrées du menu dans le registre, sans réinstaller le package. */
	public static void refreshEntries(final List<MenuItem> items, final Path configDir, final String photoFolder) {
		final String launcher = Launchers.appDir().orElseThrow().resolve(Launchers.GUI_EXE).toString();
		ClassicMenuInstaller.importRegFile(buildRegFileContent(items, launcher, configDir, photoFolder));
	}

	/** Retire le package et la configuration du menu. */
	public static void uninstall() {
		powershell("Get-AppxPackage -Name " + PACKAGE_NAME + " | Remove-AppxPackage");
		ProcessRunner.run(List.of("reg", "delete", "HKCU\\Software\\synoRight", "/f"));
	}

	public static boolean isInstalled() {
		return ProcessRunner.isWindows()
				&& powershell("if (Get-AppxPackage -Name " + PACKAGE_NAME + ") { 'installe' }").output().contains("installe");
	}

	static String buildRegFileContent(final List<MenuItem> items, final String launcherExe, final Path configDir, final String photoFolder) {
		final StringBuilder reg = new StringBuilder("Windows Registry Editor Version 5.00\r\n");
		// suppression préalable : entrées obsolètes
		reg.append("\r\n[-").append(MENU_KEY).append("]\r\n");
		reg.append("\r\n[").append(MENU_KEY).append("]\r\n")
				.append("\"Launcher\"=\"").append(escape(launcherExe)).append("\"\r\n")
				.append("\"ConfigDir\"=\"").append(escape(configDir.toString())).append("\"\r\n")
				.append("\"PhotoFolder\"=\"").append(escape(photoFolder)).append("\"\r\n");
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

	/** Approuve le certificat du package (magasin machine TrustedPeople) : une élévation UAC la première fois. */
	private static void trustCertificate(final Path cer) {
		final String thumbprint = thumbprint(cer);
		final String check = "Test-Path " + psQuote("Cert:\\LocalMachine\\TrustedPeople\\" + thumbprint);
		if (powershell(check).output().contains("True")) {
			return;
		}
		ConsoleUtil.info("Windows va demander une autorisation administrateur (une seule fois) pour approuver le certificat du menu Windows 11.");
		final String importCommand = "Import-Certificate -FilePath " + psQuote(cer.toString()) + " -CertStoreLocation Cert:\\LocalMachine\\TrustedPeople";
		powershell("Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList '-NoProfile','-Command',"
				+ psQuote(importCommand));
		if (!powershell(check).output().contains("True")) {
			throw new UserException("Certificat du menu Windows 11 non approuvé (autorisation refusée ?) : menu non installé. Le menu classique reste disponible.");
		}
	}

	// empreinte SHA-1 (thumbprint Windows) du certificat
	static String thumbprint(final Path cer) {
		try (InputStream in = Files.newInputStream(cer)) {
			final Certificate certificate = CertificateFactory.getInstance("X.509").generateCertificate(in);
			return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1").digest(certificate.getEncoded()));
		} catch (final IOException | GeneralSecurityException e) {
			throw new UserException("Certificat du menu illisible : " + cer, e);
		}
	}
}
