package sk.synoAdmin.cli;

import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import sk.synoAdmin.engine.RightEngine;
import sk.synoAdmin.i18n.Messages;
import sk.synoAdmin.preview.PreviewImages.Preview;
import sk.synoAdmin.preview.PreviewItem;
import sk.synoAdmin.preview.PreviewLoader;
import sk.synoAdmin.preview.PreviewPicker;

/**
 * Commande « previews » (cachée) : diagnostic des aperçus photo d'un dossier, un élément par ligne.
 */
@Command(name = "previews", hidden = true)
public final class PreviewsCommand extends AbstractCommand {

	@Parameters(index = "0", arity = "0..1", paramLabel = "<folder>", defaultValue = ".", descriptionKey = "cli.param.folder")
	String folder;

	@Override
	protected void execute() {
		final RightEngine re = new RightEngine(loadPrefs());
		re.checkFolder(folder);
		re.login();

		final long start = System.nanoTime();
		final List<PreviewItem> items = PreviewPicker.pick(re.folderId(folder), Paths.get(folder),
				PreviewPicker.PhotoSource.of(re.client()));
		if (items.isEmpty()) {
			System.out.println(Messages.get("cli.previews.empty"));
			return;
		}
		final PreviewLoader loader = new PreviewLoader(re.client());
		for (final PreviewItem item : items) {
			final Preview preview = loader.load(item);
			System.out.println(Messages.get("cli.previews.line",
					Messages.get("cli.previews.source." + preview.source().name().toLowerCase(Locale.ROOT)),
					item.file(), preview.reason())); // chemin complet : diagnostic des lecteurs réseau / chemins UNC
		}
		System.out.println(Messages.get("cli.previews.summary", items.size(), (System.nanoTime() - start) / 1_000_000));
	}
}
