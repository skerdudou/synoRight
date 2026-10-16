package sk.synoAdmin.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import sk.synoAdmin.LogUtil;
import sk.synoAdmin.prefs.Preferences;

/**
 * Base des sous-commandes : accès aux options communes et à la configuration.
 */
public abstract class AbstractCommand implements Callable<Integer> {

	@Spec
	CommandSpec spec;

	@Override
	public final Integer call() {
		if (root().verbose) {
			LogUtil.setVerbose(true);
		}
		execute();
		return 0;
	}

	protected abstract void execute();

	SynoRightCommand root() {
		return (SynoRightCommand) spec.root().userObject();
	}

	protected Path configDir() {
		return Preferences.resolveConfigDir(root().configDir);
	}

	/**
	 * Lit la configuration (et active le mode verbeux si elle le demande).
	 */
	protected Preferences loadPrefs() {
		final Preferences pref = Preferences.fromFile(configDir());
		if (pref.isVerbose()) {
			LogUtil.setVerbose(true);
		}
		return pref;
	}
}
