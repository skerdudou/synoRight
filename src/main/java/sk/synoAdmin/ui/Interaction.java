package sk.synoAdmin.ui;

import java.util.List;

/**
 * Saisies et messages destinés à l'utilisateur : console (CLI) ou dialogues (IHM).
 * Une saisie annulée dans l'IHM lève une UserException("Saisie annulée.").
 */
public interface Interaction {

	/** Demande une valeur non vide. */
	String promptLine(String label);

	/** Demande une valeur, une saisie vide conservant la valeur par défaut (si non nulle). */
	String promptLine(String label, String defaultValue);

	/** Fait choisir un ou plusieurs éléments ; renvoie les index (base 0), sans doublon. */
	List<Integer> promptChoices(String label, List<String> items);

	/** Demande une valeur sensible non vide (saisie masquée). */
	String promptSecret(String label);

	/** Demande une valeur sensible ; saisie vide : null (conserver la valeur actuelle). */
	String promptSecretOrKeep(String label);

	/** Pose une question fermée oui/non. */
	boolean promptBool(String label);

	/** Affiche une information. */
	void info(String message);

	/**
	 * Affiche une erreur.
	 *
	 * @param detail cause à détailler (stack trace), null pour le message seul
	 */
	void error(String message, Throwable detail);

	/** true pour l'IHM (dialogues), false pour la console. */
	boolean isGraphical();
}
