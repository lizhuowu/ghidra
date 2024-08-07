/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.plugin.core.symtable;

import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.*;
import javax.swing.event.TableModelListener;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;

import org.jdom.Element;

import docking.widgets.checkbox.GCheckBox;
import docking.widgets.table.DefaultRowFilterTransformer;
import docking.widgets.table.RowFilterTransformer;
import ghidra.app.plugin.core.symtable.AbstractSymbolTableModel.OriginalNameColumn;
import ghidra.framework.options.SaveState;
import ghidra.framework.plugintool.PluginTool;
import ghidra.program.model.symbol.Symbol;
import ghidra.util.table.*;

class SymbolPanel extends JPanel {

	private static final boolean FILTER_NAME_ONLY_DEFAULT = true;

	private static final String FILTER_SETTINGS_ELEMENT_NAME = "FILTER_SETTINGS";

	private SymbolProvider symProvider;
	private SymbolTableModel tableModel;
	private GhidraTable symTable;
	private TableModelListener listener;
	private FilterDialog filterDialog;
	private GhidraThreadedTablePanel<SymbolRowObject> threadedTablePanel;
	private GhidraTableFilterPanel<SymbolRowObject> tableFilterPanel;

	SymbolPanel(SymbolProvider provider, SymbolTableModel model, SymbolRenderer renderer,
			PluginTool tool) {

		super(new BorderLayout());

		this.symProvider = provider;
		this.tableModel = model;

		threadedTablePanel = new GhidraThreadedTablePanel<>(model);

		this.listener = e -> symProvider.updateTitle();

		symTable = threadedTablePanel.getTable();
		symTable.setAutoLookupColumn(AbstractSymbolTableModel.LABEL_COL);
		symTable.setRowSelectionAllowed(true);
		symTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		symTable.getModel().addTableModelListener(listener);
		symTable.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				handleTableSelection();
				tool.contextChanged(symProvider);
			}
		});

		symTable.setAccessibleNamePrefix("Symbol");

		symTable.installNavigation(tool);

		for (int i = 0; i < symTable.getColumnCount(); i++) {
			TableColumn column = symTable.getColumnModel().getColumn(i);
			column.setCellRenderer(renderer);
			if (column.getModelIndex() == AbstractSymbolTableModel.LABEL_COL) {
				column.setCellEditor(new SymbolEditor());
			}
		}

		add(threadedTablePanel, BorderLayout.CENTER);
		add(createFilterFieldPanel(), BorderLayout.SOUTH);

		filterDialog = new FilterDialog(tool);

		// enable dragging symbols out of the symbol table
		new SymbolTableDragProvider(symTable, model);
	}

	private JPanel createFilterFieldPanel() {
		tableFilterPanel = new GhidraTableFilterPanel<>(symTable, tableModel);
		tableFilterPanel.setToolTipText("Filters the contents of the table on symbol " +
			"names that start with the given pattern");

		tableFilterPanel.add(Box.createHorizontalStrut(5));

		final JCheckBox nameColumnOnlyCheckbox = new GCheckBox("Name Only");
		nameColumnOnlyCheckbox.setName("NameOnly"); // used by JUnit
		nameColumnOnlyCheckbox.setToolTipText(
			"<html><b>Selected</b> causes filter to only consider the symbol's name.");
		nameColumnOnlyCheckbox.setFocusable(false);
		nameColumnOnlyCheckbox.setSelected(FILTER_NAME_ONLY_DEFAULT);
		tableFilterPanel
				.setFilterRowTransformer(updateRowDataTransformer(FILTER_NAME_ONLY_DEFAULT));
		nameColumnOnlyCheckbox.addItemListener(e -> {
			boolean nameOnly = nameColumnOnlyCheckbox.isSelected();
			tableFilterPanel.setFilterRowTransformer(updateRowDataTransformer(nameOnly));
		});

		tableFilterPanel.add(nameColumnOnlyCheckbox);

		tableFilterPanel.setAccessibleNamePrefix("Symbol");
		return tableFilterPanel;
	}

	protected RowFilterTransformer<SymbolRowObject> updateRowDataTransformer(boolean nameOnly) {
		TableColumnModel columnModel = symTable.getColumnModel();
		if (nameOnly) {
			return new NameOnlyRowTransformer(tableModel, columnModel);
		}
		return new DefaultRowFilterTransformer<>(tableModel, columnModel);
	}

	void dispose() {
		symTable.getModel().removeTableModelListener(listener);
		symTable.dispose();
		threadedTablePanel.dispose();
		tableFilterPanel.dispose();
		filterDialog.dispose();
	}

	void setFilter() {
		if (filterDialog == null) {
			return;
		}
		if (symTable.isEditing()) {
			symTable.editingCanceled(null);
		}
		symProvider.setCurrentSymbol(null);
		symTable.clearSelection();

		filterDialog.adjustFilter(symProvider, tableModel);
	}

	SymbolFilter getFilter() {
		return tableModel.getFilter();
	}

	void readConfigState(SaveState saveState) {
		Element filterElement = saveState.getXmlElement(FILTER_SETTINGS_ELEMENT_NAME);
		if (filterElement != null) {
			filterDialog.restoreFilter(filterElement);
			tableModel.setFilter(filterDialog.getFilter());
		}
	}

	void writeConfigState(SaveState saveState) {
		Element filterElement = filterDialog.saveFilter();
		saveState.putXmlElement(FILTER_SETTINGS_ELEMENT_NAME, filterElement);
	}

	private void handleTableSelection() {
		int selectedRowCount = symTable.getSelectedRowCount();

		if (selectedRowCount == 1) {
			int selectedRow = symTable.getSelectedRow();
			Symbol symbol = symProvider.getSymbolForRow(selectedRow);
			symProvider.setCurrentSymbol(symbol); // null allowed
		}
		else {
			symProvider.setCurrentSymbol(null);
		}
	}

	int getActualSymbolCount() {
		return symTable.getRowCount();
	}

	List<Symbol> getSelectedSymbols() {
		List<Symbol> list = new ArrayList<>();
		int[] rows = symTable.getSelectedRows();
		for (SymbolRowObject rowObject : tableModel.getRowObjects(rows)) {
			Symbol s = rowObject.getSymbol();
			if (s != null) {
				list.add(s);
			}
		}
		return list;
	}

	GhidraTable getTable() {
		return symTable;
	}

//==================================================================================================
// Inner Classes
//==================================================================================================

	private static class NameOnlyRowTransformer
			extends DefaultRowFilterTransformer<SymbolRowObject> {

		private List<String> list = new ArrayList<>();
		private SymbolTableModel symbolTableModel;

		NameOnlyRowTransformer(SymbolTableModel symbolTableModel, TableColumnModel columnModel) {
			super(symbolTableModel, columnModel);
			this.symbolTableModel = symbolTableModel;
		}

		@Override
		public List<String> transform(SymbolRowObject rowObject) {
			list.clear();
			if (rowObject != null) {
				// The toString() returns the name for the symbol, which may be cached.  Calling
				// toString() will also avoid locking for cached values.
				list.add(rowObject.toString());

				// Add the 'Original Imported Name' value as well, which may feel intuitive to the
				// user when filtering on the name.
				addOriginalName(rowObject);
			}
			return list;
		}

		private void addOriginalName(SymbolRowObject rowObject) {
			int index = symbolTableModel.getColumnIndex(OriginalNameColumn.class);
			String originalName = getStringValue(rowObject, index);
			if (originalName != null) {
				list.add(originalName);
			}
		}

		@Override
		public int hashCode() {
			// not meant to put in hashing structures; the data for equals may change over time
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null) {
				return false;
			}
			if (getClass() != obj.getClass()) {
				return false;
			}
			return true;
		}
	}
}
