package sk.synoAdmin.engine;

public class Group {

	public static final String FILE_EXT = ".synoRight";

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
