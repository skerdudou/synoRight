package sk.synoAdmin;

/**
 * Point d'entrée du lanceur sans console (synoRightGui.exe) : exécute la sous-commande 'gui'.
 */
public final class GuiMain {

	private GuiMain() {
		// point d'entrée
	}

	public static void main(final String[] args) {
		final String[] guiArgs = new String[args.length + 1];
		guiArgs[0] = "gui";
		System.arraycopy(args, 0, guiArgs, 1, args.length);
		// toujours quitter : les threads AWT ne doivent pas maintenir la JVM
		System.exit(Main.run(guiArgs));
	}
}
