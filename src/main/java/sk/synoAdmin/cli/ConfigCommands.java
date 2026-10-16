package sk.synoAdmin.cli;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import sk.synoAdmin.ConsoleUtil;
import sk.synoAdmin.UserException;
import sk.synoAdmin.engine.Group;
import sk.synoAdmin.engine.GroupChecks;
import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.menu.MenuEntry;
import sk.synoAdmin.menu.MenuService;
import sk.synoAdmin.menu.MenuTarget;
import sk.synoAdmin.prefs.Preferences;
import sk.synoAdmin.prefs.Session;
import sk.synoAdmin.synology.SynoApiClient;
import sk.synoAdmin.synology.SynoGroup;

/**
 * Commandes de gestion de la configuration, de la session et du menu contextuel.
 */
final class ConfigCommands {

	private ConfigCommands() {
		// conteneur
	}

	static String describeGroups(final List<Group> groups) {
		return groups.stream().map(g -> g.getName() + " (id " + g.getId() + ")").collect(Collectors.joining(", "));
	}

	/**
	 * Signale les groupes configurés absents du NAS ou renommés.
	 *
	 * @return true si tout correspond
	 */
	static boolean checkGroups(final List<SynoGroup> nasGroups, final List<Group> groups) {
		final List<String> warnings = GroupChecks.warnings(nasGroups, groups);
		warnings.forEach(System.out::println);
		return warnings.isEmpty();
	}

	static String describe(final MenuService.Status status) {
		if (status.classic() && status.modern()) {
			return Messages.get("cli.menu.state.both");
		}
		if (status.modern()) {
			return Messages.get("cli.menu.state.modern");
		}
		return Messages.get(status.classic() ? "cli.menu.state.classic" : "cli.menu.state.none");
	}

	// les entrées de groupes du menu contextuel suivent la configuration
	private static void refreshMenu(final Preferences pref) {
		try {
			MenuService.refresh(pref);
		} catch (final UserException e) {
			System.out.println(Messages.get("cli.menu.refreshFailed", e.getMessage()));
		}
	}

	// ligne « libellé : valeur » de la commande config, libellés alignés sur les deux-points
	private static void line(final String label, final Object value) {
		System.out.println(String.format("%-15s: %s", Messages.get("cli.config.label." + label), value));
	}

	@Command(name = "config")
	static final class Config extends AbstractCommand {
		@Override
		protected void execute() {
			final Preferences pref = loadPrefs();

			line("file", pref.getConfigDir().resolve(Preferences.CONFIG_FILENAME));
			line("photoFolder", pref.getNasFolder());
			line("url", pref.getUrl());
			line("account", pref.getUser());
			line("password", Messages.get(pref.getPassword() == null ? "cli.config.passwordNotStored" : "cli.config.passwordStored"));
			line("groups", describeGroups(pref.getGroups()));
			line("certificate", pref.getCertFingerprint() == null
					? Messages.get("cli.config.certStandard") : Messages.get("cli.config.certSelfSigned", pref.getCertFingerprint()));
			line("verbose", pref.isVerbose());
			line("session", Messages.get(pref.getSession().getCookieSession() == null ? "cli.config.sessionNone" : "cli.config.sessionStored"));
			line("explorerMenu", describe(MenuService.status()));
			line("menuWin11", pref.getMenuEntries(true).stream().map(MenuEntry::id).collect(Collectors.joining(", ")));
			line("menuClassic", pref.getMenuEntries(false).stream().map(MenuEntry::id).collect(Collectors.joining(", ")));
			System.out.println();

			boolean folderOk;
			try {
				folderOk = Files.isDirectory(Paths.get(pref.getNasFolder()));
			} catch (final InvalidPathException e) {
				folderOk = false;
			}
			line("photoFolder", Messages.get(folderOk ? "cli.config.folderOk" : "cli.config.folderKo"));

			final SynoApiClient client = new SynoApiClient(pref);
			client.login();
			line("nasConnection", Messages.get("cli.config.ok"));
			if (checkGroups(client.getGroups(), pref.getGroups())) {
				line("groups", Messages.get("cli.config.ok"));
			} else {
				System.out.println(Messages.get("cli.config.fix"));
			}
		}
	}

	@Command(name = "groups", subcommands = { GroupsAdd.class, GroupsRemove.class })
	static final class Groups extends AbstractCommand {
		@Override
		protected void execute() {
			final Preferences pref = loadPrefs();
			for (final Group grp : pref.getGroups()) {
				System.out.println(grp.getName() + " (id " + grp.getId() + ")");
			}
		}
	}

	/**
	 * Groupe géré pour un groupe du NAS : nom court de la configuration existante (même id) s'il y en a un,
	 * sinon le nom du NAS ; demandé en console si ce nom n'est pas utilisable (caractère interdit, doublon).
	 *
	 * @param existing configuration actuelle (null : aucune)
	 * @param chosen groupes déjà retenus (contrôle des doublons)
	 */
	static Group toGroup(final SynoGroup nasGroup, final Preferences existing, final List<Group> chosen) {
		final String id = String.valueOf(nasGroup.gid());
		String name = existing == null ? nasGroup.name() : existing.getGroupById(id).map(Group::getName).orElse(nasGroup.name());
		String reason = GroupChecks.invalidShortName(name, chosen, id);
		while (reason != null) {
			System.out.println(Messages.get("cli.groups.shortNameUnusable", name, reason));
			name = ConsoleUtil.promptLine(Messages.get("cli.groups.shortNamePrompt", nasGroup.name()));
			reason = GroupChecks.invalidShortName(name, chosen, id);
		}
		return new Group(id, name.trim());
	}

	@Command(name = "add")
	static final class GroupsAdd extends AbstractCommand {
		@Override
		protected void execute() {
			final Preferences pref = loadPrefs();
			final SynoApiClient client = new SynoApiClient(pref);
			client.login();

			final List<SynoGroup> candidates = client.getGroups().stream()
					.filter(g -> pref.getGroupById(String.valueOf(g.gid())).isEmpty())
					.sorted(Comparator.comparing(SynoGroup::name, String.CASE_INSENSITIVE_ORDER))
					.toList();
			if (candidates.isEmpty()) {
				System.out.println(Messages.get("cli.groups.allManaged"));
				return;
			}

			final List<Group> groups = new ArrayList<>(pref.getGroups());
			for (final int index : ConsoleUtil.promptChoices(Messages.get("cli.groups.prompt.add"), candidates.stream().map(SynoGroup::name).toList())) {
				groups.add(toGroup(candidates.get(index), null, groups));
			}
			pref.setGroups(groups);
			pref.save();
			System.out.println(Messages.get("cli.groups.managed", describeGroups(pref.getGroups())));
			refreshMenu(pref);
		}
	}

	@Command(name = "remove")
	static final class GroupsRemove extends AbstractCommand {
		@Override
		protected void execute() {
			final Preferences pref = loadPrefs();
			final List<Group> current = pref.getGroups();

			final List<Integer> toRemove = ConsoleUtil.promptChoices(Messages.get("cli.groups.prompt.remove"), current.stream().map(Group::getName).toList());
			final List<Group> groups = new ArrayList<>();
			for (int i = 0; i < current.size(); i++) {
				if (!toRemove.contains(i)) {
					groups.add(current.get(i));
				}
			}
			pref.setGroups(groups);
			pref.save();
			System.out.println(Messages.get("cli.groups.managed", describeGroups(pref.getGroups())));
			refreshMenu(pref);
		}
	}

	@Command(name = "password")
	static final class Password extends AbstractCommand {
		@Option(names = "--forget", descriptionKey = "cli.opt.forget")
		boolean forget;

		@Override
		protected void execute() {
			final Preferences pref = loadPrefs();
			if (forget) {
				pref.setPassword(null);
				pref.save();
				System.out.println(Messages.get("cli.password.forgotten"));
				return;
			}

			final String[] password = { ConsoleUtil.promptSecret(Messages.get("cli.password.prompt", pref.getUser())) };
			// vrai login sur une session de travail pour vérifier le mot de passe (le device id évite l'OTP)
			final Session workSession = pref.getSession().transientCopy(true);
			new SynoApiClient(pref.getUrl(), pref.getUser(), password[0], false, workSession, pref.getCertFingerprint(),
					newPassword -> password[0] = newPassword).login();

			pref.getSession().replaceWith(workSession);
			pref.setPassword(password[0]);
			pref.save();
			System.out.println(Messages.get("cli.password.saved"));
		}
	}

	@Command(name = "logout")
	static final class Logout extends AbstractCommand {
		@Override
		protected void execute() {
			Session.load(configDir()).clear();
			System.out.println(Messages.get("cli.logout.done"));
		}
	}

	@Command(name = "menu", subcommands = { MenuInstall.class, MenuUninstall.class })
	static final class Menu extends AbstractCommand {
		@Override
		protected void execute() {
			System.out.println(Messages.get("cli.menu.status", describe(MenuService.status())));
		}
	}

	@Command(name = "install")
	static final class MenuInstall extends AbstractCommand {
		@ArgGroup(exclusive = true)
		TargetOption targetOption;

		static final class TargetOption {
			@Option(names = "--modern", descriptionKey = "cli.opt.modern")
			boolean modern;
			@Option(names = "--classic", descriptionKey = "cli.opt.classic")
			boolean classic;
			@Option(names = "--all", descriptionKey = "cli.opt.all")
			boolean all;
		}

		MenuTarget target() {
			if (targetOption == null) {
				return null;
			}
			return targetOption.modern ? MenuTarget.MODERN : targetOption.classic ? MenuTarget.CLASSIC : MenuTarget.ALL;
		}

		@Override
		protected void execute() {
			final Preferences pref = loadPrefs();
			if (target() != null) {
				pref.setMenuTarget(target());
				pref.save();
			}
			System.out.println(MenuService.install(pref, MenuService.effectiveTarget(pref)));
		}
	}

	@Command(name = "uninstall")
	static final class MenuUninstall extends AbstractCommand {
		@Override
		protected void execute() {
			MenuService.uninstall();
			System.out.println(Messages.get("cli.menu.uninstalled"));
		}
	}
}
