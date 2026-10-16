package sk.synoAdmin.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.swing.table.AbstractTableModel;

import sk.synoAdmin.engine.DeltaResolver.GrpDelta;
import sk.synoAdmin.engine.Group;
import sk.synoAdmin.engine.SyncPlan;

/**
 * Écarts entre fichiers et Synology Photos : une ligne par dossier et par groupe, triée par dossier puis groupe.
 */
final class SyncTableModel extends AbstractTableModel {
	private static final String[] COLUMNS = { "Dossier", "Groupe", "Synology Photos", "Fichier" };

	/** Un écart : présence du groupe sur le dossier dans Synology Photos et dans le fichier de droits. */
	record Row(String folder, String group, boolean inPhotos, boolean inFile) {
	}

	private final List<Row> rows = new ArrayList<>();

	/** @param push plan fichiers → Photos : « à ajouter » = dans le fichier seulement, « à retirer » = dans Photos seulement */
	SyncTableModel(final SyncPlan push) {
		for (final Map.Entry<Group, GrpDelta> entry : push.deltas().entrySet()) {
			final String group = entry.getKey().getName();
			entry.getValue().getToAdd().forEach(f -> rows.add(new Row(f.getName(), group, false, true)));
			entry.getValue().getToRemove().forEach(f -> rows.add(new Row(f.getName(), group, true, false)));
		}
		rows.sort(Comparator.comparing(Row::folder).thenComparing(Row::group));
	}

	@Override
	public int getRowCount() {
		return rows.size();
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
	public Object getValueAt(final int row, final int column) {
		final Row r = rows.get(row);
		return switch (column) {
			case 0 -> r.folder();
			case 1 -> r.group();
			case 2 -> r.inPhotos() ? "oui" : "non";
			default -> r.inFile() ? "oui" : "non";
		};
	}
}
