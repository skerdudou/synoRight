package sk.synoAdmin.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.swing.table.AbstractTableModel;

import sk.synoAdmin.engine.DeltaResolver.GrpDelta;
import sk.synoAdmin.engine.Group;
import sk.synoAdmin.engine.SyncPlan;
import sk.synoAdmin.i18n.Messages;

/**
 * Écarts entre fichiers et Synology Photos : une ligne par dossier et par groupe, triée par dossier puis groupe.
 */
final class SyncTableModel extends AbstractTableModel {
	private static final int COLUMN_COUNT = 4;

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
		return COLUMN_COUNT;
	}

	@Override
	public String getColumnName(final int column) {
		return switch (column) {
			case 0 -> Messages.get("gui.column.folder");
			case 1 -> Messages.get("gui.column.group");
			case 2 -> "Synology Photos";
			default -> Messages.get("gui.column.file");
		};
	}

	@Override
	public Object getValueAt(final int row, final int column) {
		final Row r = rows.get(row);
		return switch (column) {
			case 0 -> r.folder();
			case 1 -> r.group();
			case 2 -> FolderRightsTableModel.yesNo(r.inPhotos());
			default -> FolderRightsTableModel.yesNo(r.inFile());
		};
	}
}
