package sk.synoAdmin.gui;

import java.util.Map;
import java.util.Set;

import javax.swing.table.TableModel;

import sk.synoAdmin.engine.Group;

/**
 * Tableau de la fenêtre des droits : un dossier (une ligne par groupe) ou plusieurs (une ligne par dossier).
 */
interface RightsTableModel extends TableModel {

	/** Modification de case, ou groupe dont la cible ne correspond pas aux deux côtés (écart à corriger). */
	boolean hasPendingChanges();

	/** Groupes cochés, par dossier (chemin tel que reçu). */
	Map<String, Set<Group>> wanted();

	/** Écart entre Synology Photos et le fichier sur la cellule. */
	boolean hasGap(int row, int column);

	/** Info-bulle de la cellule, null si aucune. */
	String toolTip(int row, int column);
}
