package sk.synoAdmin.cli;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import picocli.CommandLine.Command;
import sk.synoAdmin.ConsoleUtil;
import sk.synoAdmin.UserException;
import sk.synoAdmin.engine.Group;
import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.menu.MenuService;
import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.prefs.Session;
import sk.synoAdmin.synology.ConnectionCheck;
import sk.synoAdmin.synology.SynoApiClient;
import sk.synoAdmin.synology.SynoGroup;

/**
 * Assistant de configuration : connexion au NAS (validée avant d'aller plus loin),
 * dossier photos, choix des groupes parmi ceux du NAS, puis menu contextuel.
 * Relancé sur une configuration existante, il propose les valeurs actuelles.
 */
@Command(name = "init")
final class InitCommand extends AbstractCommand {

	@Override
	protected void execute() {
		final Path dir = configDir();
		final Preferences existing = loadExisting(dir);

		if (existing != null) {
			System.out.println(Messages.get("cli.init.modify", dir.resolve(Preferences.CONFIG_FILENAME)));
			System.out.println(Messages.get("cli.init.keepHint"));
		} else {
			System.out.println(Messages.get("cli.init.create", dir));
		}

		// 1. connexion, validée avant de poser les autres questions
		final Session session = Session.load(dir);
		Session workSession;
		String url = existing == null ? null : existing.getUrl();
		String user = existing == null ? null : existing.getUser();
		final String[] password = new String[1];
		String fingerprint = existing == null ? null : existing.getCertFingerprint();
		SynoApiClient client;
		while (true) {
			url = ConnectionCheck.normalizeUrl(ConsoleUtil.promptLine(Messages.get("cli.init.prompt.url"), url));
			user = ConsoleUtil.promptLine(Messages.get("cli.init.prompt.user"), user);

			final boolean sameAccount = existing != null && url.equals(ConnectionCheck.normalizeUrl(existing.getUrl())) && user.equals(existing.getUser());
			final boolean keepPassword;
			if (sameAccount && existing.getPassword() != null) {
				password[0] = ConsoleUtil.promptSecretOrKeep(Messages.get("cli.init.prompt.passwordKeep"));
				keepPassword = password[0] == null;
				if (keepPassword) {
					password[0] = existing.getPassword();
				}
			} else {
				password[0] = ConsoleUtil.promptSecret(Messages.get("cli.init.prompt.password"));
				keepPassword = false;
			}

			try {
				// mot de passe éventuellement corrigé pendant le login : c'est lui qui sera mémorisé
				final ConnectionCheck.Result result = ConnectionCheck.connect(url, user, password[0], keepPassword, session, sameAccount, fingerprint);
				client = result.client();
				workSession = result.workSession();
				password[0] = result.password();
				fingerprint = result.fingerprint();
				System.out.println(Messages.get("cli.init.connected"));
				break;
			} catch (final UserException e) {
				System.out.println(e.getMessage());
			}
			if (!ConsoleUtil.promptBool(Messages.get("cli.init.retry"))) {
				throw new UserException(Messages.get("cli.init.aborted"));
			}
		}

		// 2. dossier photos
		final String folder = promptFolder(existing == null ? null : existing.getNasFolder());

		// 3. groupes, choisis parmi ceux du NAS
		final List<Group> groups = promptGroups(client, existing);

		// mot de passe volontairement non mémorisé (password --forget) : on redemande
		final boolean storePassword = existing == null || existing.getPassword() != null
				|| ConsoleUtil.promptBool(Messages.get("cli.init.storePassword"));

		session.replaceWith(workSession);
		final Preferences pref = new Preferences(dir, folder, url, user, storePassword ? password[0] : null, groups, existing != null && existing.isVerbose());
		pref.setCertFingerprint(fingerprint);
		pref.save();
		System.out.println(Messages.get("cli.init.saved", dir.resolve(Preferences.CONFIG_FILENAME)));

		// 4. menu contextuel
		if (System.getProperty("os.name", "").toLowerCase().startsWith("windows")
				&& ConsoleUtil.promptBool(Messages.get("cli.init.installMenu"))) {
			try {
				System.out.println(MenuService.install(pref, MenuService.effectiveTarget(pref)));
			} catch (final UserException e) {
				System.out.println(Messages.get("cli.init.menuNotInstalled", e.getMessage()));
			}
		}

		System.out.println();
		System.out.println(Messages.get("cli.init.done"));
	}

	private static Preferences loadExisting(final Path dir) {
		if (!Preferences.exists(dir)) {
			return null;
		}
		try {
			return Preferences.fromFile(dir);
		} catch (final UserException e) {
			System.out.println(Messages.get("cli.init.unreadable", e.getMessage()));
			return null;
		}
	}

	private static String promptFolder(final String defaultValue) {
		while (true) {
			final String folder = ConsoleUtil.promptLine(Messages.get("cli.init.prompt.folder"), defaultValue);
			try {
				if (Files.isDirectory(Paths.get(folder))) {
					return folder;
				}
			} catch (final InvalidPathException e) {
				// message ci-dessous
			}
			System.out.println(Messages.get("cli.init.folderNotFound", folder));
		}
	}

	private static List<Group> promptGroups(final SynoApiClient client, final Preferences existing) {
		final List<SynoGroup> nasGroups = client.getGroups().stream()
				.sorted(Comparator.comparing(SynoGroup::name, String.CASE_INSENSITIVE_ORDER))
				.toList();
		if (nasGroups.isEmpty()) {
			throw new UserException(Messages.get("cli.init.noGroup"));
		}

		if (existing != null) {
			System.out.println(Messages.get("cli.init.currentGroups", ConfigCommands.describeGroups(existing.getGroups())));
			if (ConsoleUtil.promptBool(Messages.get("cli.init.keepGroups"))) {
				ConfigCommands.checkGroups(nasGroups, existing.getGroups());
				return existing.getGroups();
			}
		}

		System.out.println(Messages.get("cli.init.nasGroups"));
		final List<String> labels = nasGroups.stream()
				.map(g -> g.name() + (existing != null && existing.getGroupById(String.valueOf(g.gid())).isPresent() ? " " + Messages.get("cli.init.groupCurrent") : ""))
				.toList();
		// groupe déjà géré : son nom court (nom des fichiers de droits) est conservé
		final List<Group> groups = new ArrayList<>();
		for (final int index : ConsoleUtil.promptChoices(Messages.get("cli.init.prompt.groups"), labels)) {
			groups.add(ConfigCommands.toGroup(nasGroups.get(index), existing, groups));
		}
		return groups;
	}
}
