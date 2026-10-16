package sk.synoAdmin.gui;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.swing.table.AbstractTableModel;

import sk.synoAdmin.engine.FolderRights;
import sk.synoAdmin.engine.Group;

/**
 * Modèle du tableau des droits d'un dossier : case « Autorisé » éditable, état Photos et fichier en lecture seule.
 */
final class FolderRightsTableModel extends AbstractTableModel {

	private static final String[] COLUMNS = { "Autorisé", "Groupe", "Synology Photos", "Fichier" };

	private final List<FolderRights.GroupRight> rights;
	private final boolean[] wanted;

	FolderRightsTableModel(final FolderRights folderRights) {
		this.rights = folderRights.rights();
		this.wanted = new boolean[rights.size()];
		for (int i = 0; i < wanted.length; i++) {
			wanted[i] = rights.get(i).inPhotos();
		}
	}

	@Override
	public int getRowCount() {
		return rights.size();
	}

	@Override
	public int getColumnCount() {
		return COLUMNS.length;
	}

	@Override
	public String getColumnName(final int column) {
		return COLUMNS[column];
	}

	@Override
	public Class<?> getColumnClass(final int column) {
		return column == 0 ? Boolean.class : String.class;
	}

	@Override
	public boolean isCellEditable(final int row, final int column) {
		return column == 0;
	}

	@Override
	public Object getValueAt(final int row, final int column) {
		final FolderRights.GroupRight right = rights.get(row);
		return switch (column) {
			case 0 -> wanted[row];
			case 1 -> right.group().getName();
			case 2 -> right.inPhotos() ? "oui" : "non";
			default -> right.inFile() ? "oui" : "non";
		};
	}

	@Override
	public void setValueAt(final Object value, final int row, final int column) {
		if (column == 0) {
			wanted[row] = Boolean.TRUE.equals(value);
			fireTableRowsUpdated(row, row);
		}
	}

	/** Groupes cochés. */
	Set<Group> wantedGroups() {
		final Set<Group> groups = new LinkedHashSet<>();
		for (int i = 0; i < wanted.length; i++) {
			if (wanted[i]) {
				groups.add(rights.get(i).group());
			}
		}
		return groups;
	}

	/** Une case diffère de l'état initial dans Synology Photos. */
	boolean isModified() {
		for (int i = 0; i < wanted.length; i++) {
			if (wanted[i] != rights.get(i).inPhotos()) {
				return true;
			}
		}
		return false;
	}

	/** Modification de case, ou groupe dont la cible ne correspond pas aux deux côtés (écart à corriger). */
	boolean hasPendingChanges() {
		for (int i = 0; i < wanted.length; i++) {
			final FolderRights.GroupRight right = rights.get(i);
			if (wanted[i] != right.inPhotos() || wanted[i] != right.inFile()) {
				return true;
			}
		}
		return false;
	}

	/** Écart entre Synology Photos et le fichier pour cette ligne. */
	boolean hasGap(final int row) {
		return rights.get(row).hasGap();
	}
}
