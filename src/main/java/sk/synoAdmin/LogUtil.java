package sk.synoAdmin;

import java.util.function.Supplier;

public class LogUtil {
	public static void runMonitored(final Runnable fct, final String message) {
		getMonitored(() -> {
			fct.run();
			return null;
		}, message);
	}

	public static <T extends Object> T getMonitored(final Supplier<T> fct, final String message) {
		final long start = System.nanoTime();

		final T ret = fct.get();

		final long end = System.nanoTime();
		System.out.println(message + " : " + (end - start) / 1000000);

		return ret;
	}
}
