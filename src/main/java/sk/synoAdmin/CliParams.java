package sk.synoAdmin;

import java.io.File;

import picocli.CommandLine.Option;

public final class CliParams {

	@Option(names = { "-c", "--config" }, defaultValue = "./", description = "Répertoire contenant la configuration. Par défaut, le répertoire courant.")
	private String configFolder;

	@Option(names = { "-d", "--directory" }, description = "Répertoire à cibler")
	private File targetFolder;

	@Option(names = { "-g", "--group" }, description = "Id du groupe à ajouter ou supprimer")
	private String gid;

	@Option(names = { "-a", "--action" }, required = true, description = "Actions, parmis : ${COMPLETION-CANDIDATES}")
	private Actions action;

	@Option(names = { "-h", "--help" }, usageHelp = true, description = "display this help message")
	private boolean usageHelpRequested;

	public enum Actions {
		INIT, ADD_GRP, DEL_GRP, FULL_DB_TO_FS, FULL_FS_TO_DB
	}

	public String getConfigFolder() {
		return configFolder;
	}

	public File getTargetFolder() {
		return targetFolder;
	}

	public String getGid() {
		return gid;
	}

	public Actions getAction() {
		return action;
	}

	public boolean isUsageHelpRequested() {
		return usageHelpRequested;
	}

}
