package sk.synoAdmin.engine;

import sk.synoAdmin.UserException;

/**
 * Dossier présent sur le partage mais pas (encore) connu de Synology Photos : peut se résoudre en attendant l'indexation.
 */
public class NotIndexedException extends UserException {

	private static final long serialVersionUID = 1L;

	public NotIndexedException(final String message) {
		super(message);
	}
}
