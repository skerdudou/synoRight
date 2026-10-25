package sk.synoAdmin.menu;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repère de l'installation des menus (HKCU\Software\synoRight\Install) : version installée, chemins utilisés, empreinte du
 * certificat approuvé, version et lanceur de la dernière réparation tentée (null après une installation réussie). Champs null
 * si absents.
 */
public record InstallMarker(String version, String launcher, String configDir, String certThumbprint, String attemptedVersion,
		String attemptedLauncher) {

	static final String KEY = "HKEY_CURRENT_USER\\Software\\synoRight\\Install";
	static final String ROOT_KEY = "HKEY_CURRENT_USER\\Software\\synoRight";

	// ligne de « reg query » : « Nom    REG_SZ    valeur » (la valeur peut contenir des espaces)
	private static final Pattern VALUE_LINE = Pattern.compile("^\\s+(\\w+)\\s+REG_SZ\\s+(.*?)\\s*$");

	/** Analyse la sortie de « reg query KEY » ; vide si aucune valeur reconnue. */
	static Optional<InstallMarker> parse(final String regQueryOutput) {
		if (regQueryOutput == null) {
			return Optional.empty();
		}
		String version = null;
		String launcher = null;
		String configDir = null;
		String certThumbprint = null;
		String attemptedVersion = null;
		String attemptedLauncher = null;
		boolean found = false;
		for (final String line : regQueryOutput.split("\\r?\\n")) {
			final Matcher matcher = VALUE_LINE.matcher(line);
			if (!matcher.matches()) {
				continue;
			}
			final String value = matcher.group(2);
			switch (matcher.group(1)) {
				case "Version" -> version = value;
				case "Launcher" -> launcher = value;
				case "ConfigDir" -> configDir = value;
				case "CertThumbprint" -> certThumbprint = value;
				case "AttemptedVersion" -> attemptedVersion = value;
				case "AttemptedLauncher" -> attemptedLauncher = value;
				default -> {
					continue;
				}
			}
			found = true;
		}
		return found ? Optional.of(new InstallMarker(version, launcher, configDir, certThumbprint, attemptedVersion, attemptedLauncher))
				: Optional.empty();
	}

	/** Contenu .reg qui remplace la clé du repère (valeurs nulles omises). */
	String toRegFile() {
		final StringBuilder reg = new StringBuilder("Windows Registry Editor Version 5.00\r\n");
		reg.append("\r\n[-").append(KEY).append("]\r\n");
		reg.append("\r\n[").append(KEY).append("]\r\n");
		append(reg, "Version", version);
		append(reg, "Launcher", launcher);
		append(reg, "ConfigDir", configDir);
		append(reg, "CertThumbprint", certThumbprint);
		append(reg, "AttemptedVersion", attemptedVersion);
		append(reg, "AttemptedLauncher", attemptedLauncher);
		return reg.toString();
	}

	private static void append(final StringBuilder reg, final String name, final String value) {
		if (value != null) {
			reg.append('"').append(name).append("\"=\"").append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\r\n");
		}
	}

	/** Repère notant une réparation tentée pour cette version et ce lanceur (le reste inchangé). */
	public InstallMarker withAttempt(final String attemptVersion, final String attemptLauncher) {
		return new InstallMarker(version, launcher, configDir, certThumbprint, attemptVersion, attemptLauncher);
	}

	/** Lit le repère dans le registre ; vide si la clé est absente ou hors Windows. */
	public static Optional<InstallMarker> read() {
		if (!ProcessRunner.isWindows()) {
			return Optional.empty();
		}
		final ProcessRunner.Result result = ProcessRunner.run(List.of("reg", "query", KEY));
		return result.ok() ? parse(result.output()) : Optional.empty();
	}

	/** Écrit le repère dans le registre. */
	public void write() {
		ClassicMenuInstaller.importRegFile(toRegFile());
	}
}
