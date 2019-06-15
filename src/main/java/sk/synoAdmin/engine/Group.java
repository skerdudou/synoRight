package sk.synoAdmin.engine;

import java.io.FilenameFilter;

public class Group {

	public static final String FILE_EXT = ".synoRight";
	public static final FilenameFilter FILE_FILTER = (d, n) -> n.endsWith(FILE_EXT);

	public Group(final String id, final String name) {
		this.id = id;
		this.name = name;
	}

	private final String id;
	private final String name;

	public String getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getFileName() {
		return "@" + name + FILE_EXT;
	}

}
