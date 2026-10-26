package sk.synoAdmin.cli;

import java.util.Optional;

import picocli.CommandLine.Command;
import sk.synoAdmin.AppVersion;
import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.update.Release;
import sk.synoAdmin.update.UpdateChecker;

/**
 * Recherche d'une version plus récente de synoRight sur GitHub.
 */
@Command(name = "update")
final class UpdateCommand extends AbstractCommand {

	@Override
	protected void execute() {
		final Optional<String> current = AppVersion.current();
		if (current.isEmpty()) {
			System.out.println(Messages.get("update.cli.devVersion"));
			return;
		}
		System.out.println(Messages.get("update.cli.current", current.get()));
		final Release latest = UpdateChecker.fetchLatestAndRecord();
		if (UpdateChecker.newer(latest, current.get())) {
			System.out.println(Messages.get("update.cli.latest", latest.version(), latest.pageUrl()));
		} else {
			System.out.println(Messages.get("update.cli.upToDate", current.get()));
		}
	}
}
