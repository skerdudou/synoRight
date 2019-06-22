package sk.synoAdmin.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Folder {
	public Folder(final File folder, final Folder parent) {
		this.folder = folder;
		name = folder.getName();
		this.parent = parent;
	}

	private String id;
	private final Folder parent;

	private final File folder;
	private final String name;

	private final List<Group> fsGroups = new ArrayList<>();
	private final List<Group> dbGroups = new ArrayList<>();

	public String getId() {
		return id;
	}

	public void setId(final String id) {
		if (this.id != null && !this.id.equals(id)) {
			throw new RuntimeException("Les ID sont non concordants entre les appels");
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

	public boolean matchParentId(final String id) {
		if (parent == null && "1".equals(id)) {
			return true;
		}

		return id.equals(parent.getId());
	}
}
