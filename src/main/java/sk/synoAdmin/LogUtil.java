package sk.synoAdmin;

import java.util.function.Supplier;

public final class LogUtil {
	// activable avec -DsynoAdmin.debug=true
	private static final boolean IS_DEBUG = Boolean.getBoolean("synoAdmin.debug");

	private LogUtil() {
		// utility
	}

	public static void runMonitored(final Runnable fct, final String message) {
		getMonitored(() -> {
			fct.run();
			return null;
		}, message);
	}

	public static <T> T getMonitored(final Supplier<T> fct, final String message) {
		final long start = System.nanoTime();

		final T ret = fct.get();

		final long end = System.nanoTime();
		System.out.println(message + " : " + (end - start) / 1_000_000 + " ms");

		return ret;
	}

	public static void debug(final String msg) {
		if (IS_DEBUG) {
			System.out.println("\t" + msg);
		}
	}
}
