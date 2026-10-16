package sk.synoAdmin.cli;

import java.util.List;

import picocli.CommandLine.Help;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Help.Ansi.IStyle;
import picocli.CommandLine.Help.Ansi.Text;
import picocli.CommandLine.Help.ColorScheme;
import picocli.CommandLine.Help.IParamLabelRenderer;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import sk.synoAdmin.i18n.Messages;

/**
 * Aide picocli dont les étiquettes de paramètres ({@code <folder>}, {@code <group>}) sont traduites :
 * picocli ne les lit pas dans le bundle, on remplace donc l'étiquette anglaise de l'annotation par la clé
 * {@code cli.label.<nom>} quand elle existe.
 */
public final class LocalizedHelp extends Help {

	private static final String LABEL_KEY_PREFIX = "cli.label.";

	public LocalizedHelp(final CommandSpec commandSpec, final ColorScheme colorScheme) {
		super(commandSpec, colorScheme);
	}

	@Override
	public IParamLabelRenderer parameterLabelRenderer() {
		final IParamLabelRenderer base = super.parameterLabelRenderer();
		final ColorScheme scheme = colorScheme();
		return new IParamLabelRenderer() {
			@Override
			public Text renderParameterLabel(final ArgSpec argSpec, final Ansi ansi, final List<IStyle> styles) {
				final Text text = base.renderParameterLabel(argSpec, ansi, styles);
				final String label = argSpec.paramLabel();
				if (!label.startsWith("<") || !label.endsWith(">")) {
					return text;
				}
				final String key = LABEL_KEY_PREFIX + label.substring(1, label.length() - 1);
				if (!Messages.bundle().containsKey(key)) {
					return text;
				}
				final String plain = text.plainString().replace(label, Messages.bundle().getString(key));
				return argSpec.isOption() ? scheme.optionParamText(plain) : scheme.parameterText(plain);
			}

			@Override
			public String separator() {
				return base.separator();
			}
		};
	}
}
