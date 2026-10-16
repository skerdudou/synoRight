package sk.synoAdmin.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import sk.synoAdmin.i18n.Messages;

public class Folder {
	public Folder(final File folder, final Folder parent) {
		this.folder = folder;
		if (parent != null) {
			name = parent.getName() + "/" + folder.getName();
		} else {
			name = "/" + folder.getName();
		}
		this.parent = parent;
	}

	private Integer id;
	private final Folder parent;

	private final File folder;
	private final String name;

	private final List<Group> fsGroups = new ArrayList<>();
	private final List<Group> dbGroups = new ArrayList<>();

	public Integer getId() {
		return id;
	}

	public void setId(final Integer id) {
		if (this.id != null && !this.id.equals(id)) {
			throw new RuntimeException(Messages.get("engine.folder.idMismatch"));
		}
		this.id = id;
	}

	public List<Group> getDbGroups() {
		return dbGroups;
	}

	public List<Group> getFsGroups() {
		return fsGroups;
	}

	public Folder getParent() {
		return parent;
	}

	public String getName() {
		return name;
	}

	public String getFolderPath() {
		return folder.getAbsolutePath();
	}

	public void addFsGroup(final Group group) {
		fsGroups.add(group);
	}

	public void addDbGroup(final Group group) {
		dbGroups.add(group);
	}

}
