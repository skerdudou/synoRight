package sk.synoAdmin.gui;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.table.AbstractTableModel;

import sk.synoAdmin.engine.FolderRights;
import sk.synoAdmin.engine.Group;
import sk.synoAdmin.i18n.Messages;

/**
 * Modèle du tableau des droits de plusieurs dossiers : une ligne par dossier, une case par groupe géré.
 * Cocher un groupe sur un sous-dossier le coche aussi sur son dossier parent s'il est dans le tableau
 * (le moteur donne de toute façon le droit au parent) ; le décocher sur un parent le décoche sur ses sous-dossiers du tableau.
 */
final class FoldersRightsTableModel extends AbstractTableModel implements RightsTableModel {

	private final List<String> folders;
	private final List<FolderRights> rights;
	private final List<Group> groups;
	private final boolean[][] wanted;

	/**
	 * @param folders chemins des dossiers tels que reçus, dans l'ordre de rights
	 */
	FoldersRightsTableModel(final List<String> folders, final List<FolderRights> rights) {
		this.folders = List.copyOf(folders);
		this.rights = List.copyOf(rights);
		this.groups = rights.getFirst().rights().stream().map(FolderRights.GroupRight::group).toList();
		this.wanted = new boolean[rights.size()][groups.size()];
		for (int row = 0; row < wanted.length; row++) {
			for (int g = 0; g < groups.size(); g++) {
				wanted[row][g] = right(row, g).inPhotos();
			}
		}
	}

	private FolderRights.GroupRight right(final int row, final int group) {
		return rights.get(row).rights().get(group);
	}

	@Override
	public int getRowCount() {
		return rights.size();
	}

	@Override
	public int getColumnCount() {
		return groups.size() + 1;
	}

	@Override
	public String getColumnName(final int column) {
		return column == 0 ? Messages.get("gui.column.folder") : groups.get(column - 1).getName();
	}

	@Override
	public Class<?> getColumnClass(final int column) {
		return column == 0 ? String.class : Boolean.class;
	}

	@Override
	public boolean isCellEditable(final int row, final int column) {
		return column > 0;
	}

	@Override
	public Object getValueAt(final int row, final int column) {
		return column == 0 ? rights.get(row).folderName() : wanted[row][column - 1];
	}

	@Override
	public void setValueAt(final Object value, final int row, final int column) {
		if (column > 0) {
			check(row, column - 1, Boolean.TRUE.equals(value));
			// parent ou sous-dossiers ont pu changer aussi ; mise à jour de lignes : la sélection est conservée
			fireTableRowsUpdated(0, getRowCount() - 1);
		}
	}

	// parent coché avec le sous-dossier, sous-dossiers décochés avec le parent (un droit sur un sous-dossier
	// sans droit sur son parent n'est pas navigable dans Synology Photos)
	private void check(final int row, final int group, final boolean value) {
		wanted[row][group] = value;
		if (value) {
			final int parent = parentRow(row);
			if (parent >= 0) {
				wanted[parent][group] = true;
			}
		} else {
			for (int child = 0; child < wanted.length; child++) {
				if (parentRow(child) == row) {
					wanted[child][group] = false;
				}
			}
		}
	}

	// ligne du dossier parent ("/a" pour "/a/b"), -1 si absent du tableau
	private int parentRow(final int row) {
		final String name = rights.get(row).folderName();
		final int slash = name.lastIndexOf('/');
		if (slash <= 0) {
			return -1;
		}
		final String parentName = name.substring(0, slash);
		for (int i = 0; i < rights.size(); i++) {
			if (rights.get(i).folderName().equalsIgnoreCase(parentName)) {
				return i;
			}
		}
		return -1;
	}

	/** Coche le groupe de la colonne sur tous les dossiers, ou le décoche partout s'il l'était déjà partout. */
	void toggleColumn(final int column) {
		if (column <= 0) {
			return;
		}
		final int group = column - 1;
		boolean all = true;
		for (final boolean[] row : wanted) {
			all &= row[group];
		}
		for (int row = 0; row < wanted.length; row++) {
			wanted[row][group] = !all;
		}
		fireTableRowsUpdated(0, getRowCount() - 1);
	}

	@Override
	public Map<String, Set<Group>> wanted() {
		final Map<String, Set<Group>> result = new LinkedHashMap<>();
		for (int row = 0; row < wanted.length; row++) {
			final Set<Group> checked = new LinkedHashSet<>();
			for (int g = 0; g < groups.size(); g++) {
				if (wanted[row][g]) {
					checked.add(groups.get(g));
				}
			}
			result.put(folders.get(row), checked);
		}
		return result;
	}

	@Override
	public boolean hasPendingChanges() {
		for (int row = 0; row < wanted.length; row++) {
			for (int g = 0; g < groups.size(); g++) {
				final FolderRights.GroupRight right = right(row, g);
				if (wanted[row][g] != right.inPhotos() || wanted[row][g] != right.inFile()) {
					return true;
				}
			}
		}
		return false;
	}

	/** Colonne dossier : écart sur au moins un groupe ; colonne groupe : écart sur ce groupe. */
	@Override
	public boolean hasGap(final int row, final int column) {
		return column == 0 ? rights.get(row).hasGap() : right(row, column - 1).hasGap();
	}

	@Override
	public String toolTip(final int row, final int column) {
		if (column == 0) {
			return folders.get(row);
		}
		final FolderRights.GroupRight right = right(row, column - 1);
		return Messages.get("gui.rights.cellTip", FolderRightsTableModel.yesNo(right.inPhotos()), FolderRightsTableModel.yesNo(right.inFile()));
	}
}
