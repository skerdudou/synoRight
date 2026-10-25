package sk.synoAdmin.cli;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import sk.synoAdmin.ConsoleUtil;
import sk.synoAdmin.engine.FolderRights;
import sk.synoAdmin.engine.Group;
import sk.synoAdmin.engine.RightEngine;
import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.prefs.Preferences;

/**
 * Commande « rights » : choisit les groupes autorisés sur un ou plusieurs dossiers.
 */
@Command(name = "rights")
public final class RightsCommand extends AbstractCommand {

	@Parameters(index = "0..*", arity = "0..*", paramLabel = "<folder>", defaultValue = ".", descriptionKey = "cli.param.folder")
	List<String> folders;

	@Override
	protected void execute() {
		final Preferences pref = loadPrefs();
		final RightEngine re = new RightEngine(pref);
		folders.forEach(re::checkFolder);
		re.login();
		final List<String> distinct = folders.stream().distinct().toList();
		final List<FolderRights> allRights = re.folderRights(distinct);

		// une saisie par dossier, application de l'ensemble à la fin
		final Map<String, Set<Group>> wanted = new LinkedHashMap<>();
		for (int f = 0; f < distinct.size(); f++) {
			wanted.put(distinct.get(f), promptGroups(allRights.get(f)));
		}

		// moteur neuf : il accumule les dossiers résolus
		final RightEngine applyEngine = new RightEngine(pref);
		applyEngine.login();
		final List<String> messages = applyEngine.setFolderRights(wanted);
		System.out.println(messages.isEmpty() ? Messages.get("cli.rights.none") : String.join(System.lineSeparator(), messages));
	}

	/** Affiche les droits du dossier et demande les groupes autorisés (Entrée : état actuel de Photos). */
	private static Set<Group> promptGroups(final FolderRights rights) {
		RightReports.printFolder(rights);
		final List<FolderRights.GroupRight> list = rights.rights();
		for (int i = 0; i < list.size(); i++) {
			System.out.println("  " + (i + 1) + ") " + list.get(i).group().getName());
		}
		final String current = IntStream.range(0, list.size()).filter(i -> list.get(i).inPhotos())
				.mapToObj(i -> String.valueOf(i + 1)).collect(Collectors.joining(","));
		Optional<Set<Integer>> selection;
		do {
			selection = parseSelection(ConsoleUtil.promptLine(Messages.get("cli.rights.prompt"),
					current.isEmpty() ? "0" : current), list.size());
		} while (selection.isEmpty());
		return selection.get().stream().map(i -> list.get(i).group()).collect(Collectors.toSet());
	}

	/** Numéros (base 1, 0 ignoré) séparés par virgule/point-virgule/espace, convertis en indices ; vide si invalide. */
	static Optional<Set<Integer>> parseSelection(final String input, final int size) {
		final Set<Integer> selection = new LinkedHashSet<>();
		for (final String part : input.split("[,; ]+")) {
			if (part.isBlank()) {
				continue;
			}
			try {
				final int value = Integer.parseInt(part.trim());
				if (value == 0) {
					continue;
				}
				if (value < 1 || value > size) {
					return Optional.empty();
				}
				selection.add(value - 1);
			} catch (final NumberFormatException e) {
				return Optional.empty();
			}
		}
		return Optional.of(selection);
	}
}
