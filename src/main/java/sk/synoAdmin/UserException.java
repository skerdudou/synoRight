package sk.synoAdmin;

public class UserException extends RuntimeException {

	public UserException() {
		super();
	}

	public UserException(final String message, final Throwable cause, final boolean enableSuppression, final boolean writableStackTrace) {
		super(message, cause, enableSuppression, writableStackTrace);
	}

	public UserException(final String message, final Throwable cause) {
		super(message, cause);
	}

	public UserException(final String message) {
		super(message);
	}

	public UserException(final Throwable cause) {
		super(cause);
	}

	private static final long serialVersionUID = -2006687183850314314L;

}
