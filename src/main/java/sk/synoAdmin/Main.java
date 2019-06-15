package sk.synoAdmin;

import sk.synoAdmin.engine.RightEngine;

public class Main {

	public static void main(final String[] args) {
		System.out.println("Start");

		final RightEngine re = new RightEngine();

		re.init();

		re.dbToFs();

		System.out.println("End");
	}

}
