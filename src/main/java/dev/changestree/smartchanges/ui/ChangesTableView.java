package dev.changestree.smartchanges.ui;

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.ThreeStateCheckBox;
import dev.changestree.smartchanges.model.ChangeArea;
import dev.changestree.smartchanges.model.ChangeInclusionModel;
import dev.changestree.smartchanges.model.ChangedItem;
import dev.changestree.smartchanges.model.SortMode;
import dev.changestree.smartchanges.settings.SmartChangesSettings;
import org.jetbrains.annotations.NotNull;

import javax.swing.AbstractAction;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.JTable;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.text.BreakIterator;

/** Table presentation and interaction; callers provide change policy and native actions. */
final class ChangesTableView extends JPanel {
    interface Listener {
        void sortBy(SortMode mode);

        void toggleInclusion(ChangesTableProjector.Row row);

        void toggleAllInclusion();

        void toggleGroup(String groupId);

        void openSelected();

        boolean isShowingHidden();

        void setHidden(List<ChangedItem> items, boolean hidden);

        void selectionChanged();
    }

    private final SmartChangesSettings settings;
    private final ChangeInclusionModel inclusionModel;
    private final Listener listener;
    private final ChangesTableModel tableModel = new ChangesTableModel();
    private final JBTable table = new JBTable(tableModel);
    private List<ChangedItem> snapshot = List.of();

    ChangesTableView(
            SmartChangesSettings settings,
            ChangeInclusionModel inclusionModel,
            ActionGroup popupGroup,
            Listener listener
    ) {
        this.settings = settings;
        this.inclusionModel = inclusionModel;
        this.listener = listener;
        setLayout(new BorderLayout());
        configureTable(createPopupGroup(popupGroup));
        add(new JBScrollPane(table), BorderLayout.CENTER);
    }

    void setRows(List<ChangesTableProjector.Row> rows, List<ChangedItem> snapshot, boolean hiddenView) {
        Set<String> selectedIds = selectedStableIds();
        this.snapshot = List.copyOf(snapshot);
        tableModel.setRows(rows);
        restoreSelection(selectedIds);
        table.getEmptyText().setText(hiddenView ? "No hidden changes" : "No changes");
        table.getTableHeader().repaint();
        table.repaint();
        listener.selectionChanged();
    }

    List<ChangedItem> selectedItems() {
        LinkedHashMap<String, ChangedItem> selected = new LinkedHashMap<>();
        for (int viewRow : table.getSelectedRows()) {
            int modelRow = table.convertRowIndexToModel(viewRow);
            if (modelRow < 0 || modelRow >= tableModel.getRowCount()) {
                continue;
            }
            ChangesTableProjector.Row row = tableModel.rowAt(modelRow);
            // Group headers are presentation rows, not VCS changes that IDE actions can target.
            if (!row.isGroup()) {
                selected.put(row.item().getStableId(), row.item());
            }
        }
        return List.copyOf(selected.values());
    }

    private void configureTable(ActionGroup popupGroup) {
        table.setRowHeight(JBUI.scale(26));
        table.setShowGrid(true);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(JBUI.size(0, 1));
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setColumnSelectionAllowed(false);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new ChangedTableCellRenderer());
        table.getColumnModel().getColumn(0).setCellRenderer(new ChangedNameCellRenderer());
        table.getColumnModel().getColumn(2).setCellRenderer(new ChangedPathCellRenderer());

        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(180));
        table.getColumnModel().getColumn(0).setMinWidth(JBUI.scale(150));
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(72));
        table.getColumnModel().getColumn(1).setMinWidth(JBUI.scale(54));
        table.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(220));
        table.getColumnModel().getColumn(2).setMinWidth(JBUI.scale(80));
        table.getColumnModel().getColumn(1).setMaxWidth(JBUI.scale(160));

        configureSortableHeader();
        table.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event) || event.isPopupTrigger() || event.getClickCount() != 1) {
                    return;
                }
                int viewColumn = table.getTableHeader().columnAtPoint(event.getPoint());
                if (viewColumn < 0) {
                    return;
                }
                if (viewColumn == table.convertColumnIndexToView(0)
                        && event.getX() - table.getTableHeader().getHeaderRect(viewColumn).x < JBUI.scale(30)) {
                    listener.toggleAllInclusion();
                    event.consume();
                    return;
                }
                listener.sortBy(SortMode.forColumn(table.convertColumnIndexToModel(viewColumn)));
            }
        });
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                listener.selectionChanged();
            }
        });

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event) || event.isPopupTrigger()) return;
                int viewRow = table.rowAtPoint(event.getPoint());
                if (viewRow < 0) {
                    return;
                }
                ChangesTableProjector.Row row = tableModel.rowAt(table.convertRowIndexToModel(viewRow));
                int viewColumn = table.columnAtPoint(event.getPoint());
                if (viewColumn == table.convertColumnIndexToView(0)
                        && isInclusionCheckboxHit(
                        tableModel.rowAt(table.convertRowIndexToModel(viewRow)),
                        event.getX() - table.getCellRect(viewRow, viewColumn, true).x)
                        && !event.isPopupTrigger()) {
                    if (event.getClickCount() == 1) {
                        listener.toggleInclusion(row);
                    }
                    event.consume();
                    return;
                }
                if (row.isGroup() && event.getClickCount() == 1 && !event.isPopupTrigger()) {
                    listener.toggleGroup(row.groupId());
                } else if (!row.isGroup() && event.getClickCount() == 2 && !event.isPopupTrigger()) {
                    listener.openSelected();
                }
            }
        });

        table.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "smartChanges.openOrDiff");
        table.getActionMap().put("smartChanges.openOrDiff", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                listener.openSelected();
            }
        });
        table.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "smartChanges.toggleInclusion");
        table.getActionMap().put("smartChanges.toggleInclusion", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                int selectedViewRow = table.getSelectionModel().getLeadSelectionIndex();
                if (selectedViewRow >= 0 && selectedViewRow < table.getRowCount()) {
                    int modelRow = table.convertRowIndexToModel(selectedViewRow);
                    listener.toggleInclusion(tableModel.rowAt(modelRow));
                }
            }
        });

        if (popupGroup != null) {
            PopupHandler.installRowSelectionTablePopup(table, popupGroup, "ChangesTreePopup");
        }
    }

    private ActionGroup createPopupGroup(ActionGroup nativeActions) {
        DefaultActionGroup popup = new DefaultActionGroup("Smart Changes Popup", false);
        popup.add(new AnAction("Hide from Changes") {
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void update(@NotNull AnActionEvent event) {
                boolean showingHidden = listener.isShowingHidden();
                event.getPresentation().setText(showingHidden ? "Restore to Changes" : "Hide from Changes");
                event.getPresentation().setEnabled(!selectedItems().isEmpty());
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                listener.setHidden(selectedItems(), !listener.isShowingHidden());
            }
        });
        if (nativeActions != null) {
            popup.addSeparator();
            popup.add(nativeActions);
        }
        return popup;
    }

    private void configureSortableHeader() {
        TableCellRenderer nativeRenderer = table.getTableHeader().getDefaultRenderer();
        TableCellRenderer sortableRenderer = (header, value, selected, focused, row, column) -> {
            Component component = nativeRenderer.getTableCellRendererComponent(
                    header, value, selected, focused, row, column);
            if (component instanceof JLabel label && column >= 0) {
                SortMode columnMode = SortMode.forColumn(table.convertColumnIndexToModel(column));
                label.setText(headerText(columnMode));
            }
            return component;
        };
        table.getTableHeader().setDefaultRenderer(sortableRenderer);
        table.getColumnModel().getColumn(0).setHeaderRenderer(new MasterHeaderCellRenderer(sortableRenderer));
    }

    private String headerText(SortMode columnMode) {
        String indicator = columnMode == settings.getSortMode()
                ? (settings.isAscending() ? "  ↑" : "  ↓")
                : "";
        return columnMode.getLabel() + indicator;
    }

    private Set<String> selectedStableIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (int viewRow : table.getSelectedRows()) {
            int modelRow = table.convertRowIndexToModel(viewRow);
            if (modelRow >= 0 && modelRow < tableModel.getRowCount()) {
                ChangesTableProjector.Row row = tableModel.rowAt(modelRow);
                if (!row.isGroup()) {
                    ids.add(row.item().getStableId());
                }
            }
        }
        return ids;
    }

    private void restoreSelection(Set<String> stableIds) {
        if (stableIds.isEmpty()) {
            return;
        }
        ListSelectionModel selection = table.getSelectionModel();
        selection.setValueIsAdjusting(true);
        try {
            for (int index = 0; index < tableModel.getRowCount(); index++) {
                ChangesTableProjector.Row row = tableModel.rowAt(index);
                if (!row.isGroup() && stableIds.contains(row.item().getStableId())) {
                    selection.addSelectionInterval(index, index);
                }
            }
        } finally {
            selection.setValueIsAdjusting(false);
        }
    }

    private ThreeStateCheckBox.State checkBoxState(ChangeInclusionModel.SelectionState state) {
        return switch (state) {
            case NONE -> ThreeStateCheckBox.State.NOT_SELECTED;
            case ALL -> ThreeStateCheckBox.State.SELECTED;
            case MIXED -> ThreeStateCheckBox.State.DONT_CARE;
        };
    }

    private boolean isInclusionCheckboxHit(ChangesTableProjector.Row row, int cellX) {
        int checkboxLeft = JBUI.scale(20 + row.indentLevel() * 12);
        int checkboxRight = checkboxLeft + JBUI.scale(24);
        return cellX >= checkboxLeft && cellX < checkboxRight;
    }

    private final class MasterHeaderCellRenderer extends JPanel implements TableCellRenderer {
        private final TableCellRenderer delegate;
        private final ThreeStateCheckBox masterCheckBox = new ThreeStateCheckBox();
        private final JLabel nameLabel = new JLabel();

        private MasterHeaderCellRenderer(TableCellRenderer delegate) {
            super(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
            this.delegate = delegate;
            setOpaque(true);
            masterCheckBox.setText("");
            masterCheckBox.setThirdStateEnabled(true);
            masterCheckBox.setOpaque(false);
            masterCheckBox.setMargin(JBUI.insets(0));
            masterCheckBox.setToolTipText("Include or exclude all changes");
            masterCheckBox.getAccessibleContext().setAccessibleName("Include all changed files");
            nameLabel.setOpaque(false);
            add(masterCheckBox);
            add(nameLabel);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable target,
                Object value,
                boolean selected,
                boolean focused,
                int rowIndex,
                int columnIndex
        ) {
            Component component = delegate.getTableCellRendererComponent(
                    target, value, selected, focused, rowIndex, columnIndex);
            if (component instanceof JLabel nativeLabel) {
                setBackground(nativeLabel.getBackground());
                setForeground(nativeLabel.getForeground());
                setFont(nativeLabel.getFont());
                setBorder(nativeLabel.getBorder());
                nameLabel.setText(nativeLabel.getText());
                nameLabel.setIcon(nativeLabel.getIcon());
                nameLabel.setFont(nativeLabel.getFont());
                nameLabel.setForeground(nativeLabel.getForeground());
            }

            masterCheckBox.setState(checkBoxState(inclusionModel.stateOf(snapshot)));
            masterCheckBox.setEnabled(snapshot.stream().anyMatch(inclusionModel::isToggleable));
            masterCheckBox.setForeground(getForeground());
            masterCheckBox.setBackground(getBackground());
            setToolTipText("Select or clear all changed files");
            return this;
        }
    }

    private final class ChangedNameCellRenderer extends JPanel implements TableCellRenderer {
        private final ThreeStateCheckBox inclusionCheckBox = new ThreeStateCheckBox();
        private final JLabel disclosureLabel = new JLabel();
        private final JLabel iconLabel = new JLabel();
        private final JLabel nameLabel = new JLabel();
        private final JPanel leadingPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));

        private ChangedNameCellRenderer() {
            super(new BorderLayout(JBUI.scale(4), 0));
            setOpaque(true);
            setBorder(JBUI.Borders.empty(0, 4));
            leadingPanel.setOpaque(false);
            inclusionCheckBox.setText("");
            inclusionCheckBox.setThirdStateEnabled(true);
            inclusionCheckBox.setOpaque(false);
            inclusionCheckBox.setMargin(JBUI.insets(0));
            disclosureLabel.setHorizontalAlignment(SwingConstants.CENTER);
            disclosureLabel.setPreferredSize(new Dimension(JBUI.scale(12), 0));
            disclosureLabel.setOpaque(false);
            iconLabel.setOpaque(false);
            nameLabel.setOpaque(false);
            nameLabel.setHorizontalAlignment(SwingConstants.LEFT);
            nameLabel.setMinimumSize(new Dimension(0, 0));
            leadingPanel.add(disclosureLabel);
            leadingPanel.add(inclusionCheckBox);
            leadingPanel.add(iconLabel);
            add(leadingPanel, BorderLayout.WEST);
            add(nameLabel, BorderLayout.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable target,
                Object value,
                boolean selected,
                boolean focused,
                int rowIndex,
                int columnIndex
        ) {
            int modelRow = target.convertRowIndexToModel(rowIndex);
            ChangesTableProjector.Row row = tableModel.rowAt(modelRow);
            Color foreground = selected ? target.getSelectionForeground() : target.getForeground();
            setBackground(selected ? target.getSelectionBackground() : target.getBackground());
            setFont(row.isGroup() ? target.getFont().deriveFont(Font.BOLD) : target.getFont());
            nameLabel.setFont(getFont());
            iconLabel.setFont(getFont());
            inclusionCheckBox.setForeground(foreground);
            nameLabel.setForeground(foreground);
            iconLabel.setForeground(foreground);
            disclosureLabel.setForeground(foreground);
            disclosureLabel.setIcon(null);
            disclosureLabel.setText("");
            leadingPanel.setBorder(JBUI.Borders.empty(0, row.indentLevel() * 12, 0, 0));

            String fullName;
            if (row.isGroup()) {
                List<ChangedItem> items = ChangesTableProjector.itemsInGroup(snapshot, row);
                inclusionCheckBox.setState(checkBoxState(inclusionModel.stateOf(items)));
                inclusionCheckBox.setEnabled(items.stream().anyMatch(inclusionModel::isToggleable));
                inclusionCheckBox.getAccessibleContext().setAccessibleName(
                        row.groupKind() == ChangesTableProjector.GroupKind.EXTENSION
                                ? "Include all " + row.groupTitle() + " files"
                                : "Include all " + row.groupTitle());
                disclosureLabel.setIcon(UIManager.getIcon(row.collapsed()
                        ? "Tree.collapsedIcon"
                        : "Tree.expandedIcon"));
                if (disclosureLabel.getIcon() == null) {
                    disclosureLabel.setText(row.collapsed() ? "▸" : "▾");
                }
                iconLabel.setIcon(null);
                fullName = row.getNameValue();
                setToolTipText(row.collapsed() ? "Expand " + row.groupTitle() : "Collapse " + row.groupTitle());
            } else {
                ChangedItem item = row.item();
                inclusionCheckBox.setState(inclusionModel.isIncluded(item)
                        ? ThreeStateCheckBox.State.SELECTED
                        : ThreeStateCheckBox.State.NOT_SELECTED);
                inclusionCheckBox.setEnabled(inclusionModel.isToggleable(item));
                inclusionCheckBox.getAccessibleContext().setAccessibleName("Include " + item.getName());
                iconLabel.setIcon(item.getFilePath().getFileType().getIcon());
                fullName = row.getNameValue().isBlank() ? item.getPath() : row.getNameValue();
                if (!selected && item.getFileStatus() != null && item.getFileStatus().getColor() != null) {
                    nameLabel.setForeground(item.getFileStatus().getColor());
                }
                setToolTipText(item.getStatus() + " — " + item.getPath()
                        + (item.getArea() == ChangeArea.CONFLICTED
                        ? " — resolve the conflict before staging"
                        : item.getGitStatusNode() != null
                        ? " — click checkbox to stage or unstage"
                        : " — include in commit"));
            }
            java.awt.Rectangle cellBounds = target.getCellRect(rowIndex, columnIndex, true);
            setSize(cellBounds.width, cellBounds.height);
            doLayout();
            leadingPanel.doLayout();
            int availableWidth = nameLabel.getWidth();
            nameLabel.setText(ellipsize(fullName, nameLabel.getFontMetrics(nameLabel.getFont()), availableWidth));
            nameLabel.setToolTipText(fullName);
            return this;
        }
    }

    private static String ellipsize(String text, FontMetrics fontMetrics, int availableWidth) {
        String ellipsis = "…";
        if (fontMetrics.stringWidth(text) <= availableWidth) {
            return text;
        }
        int ellipsisWidth = fontMetrics.stringWidth(ellipsis);
        if (availableWidth < ellipsisWidth) {
            return "";
        }

        BreakIterator boundaries = BreakIterator.getCharacterInstance(Locale.ROOT);
        boundaries.setText(text);
        int end = boundaries.preceding(text.length());
        while (end != BreakIterator.DONE && fontMetrics.stringWidth(text.substring(0, end) + ellipsis) > availableWidth) {
            end = boundaries.preceding(end);
        }
        return (end == BreakIterator.DONE ? "" : text.substring(0, end)) + ellipsis;
    }

    private static final class ChangedTableCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable target,
                Object value,
                boolean selected,
                boolean focused,
                int rowIndex,
                int columnIndex
        ) {
            super.getTableCellRendererComponent(target, value, selected, focused, rowIndex, columnIndex);
            ChangesTableProjector.Row row = ((ChangesTableModel) target.getModel())
                    .rowAt(target.convertRowIndexToModel(rowIndex));
            setFont(row.isGroup() ? target.getFont().deriveFont(Font.BOLD) : target.getFont());
            setHorizontalAlignment(SwingConstants.LEFT);
            setIcon(null);
            if (row.isGroup()) {
                setText("");
                setToolTipText(null);
            } else {
                setToolTipText(row.item().getStatus() + " — " + row.item().getPath());
            }
            return this;
        }
    }

    private static final class ChangedPathCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable target,
                Object value,
                boolean selected,
                boolean focused,
                int rowIndex,
                int columnIndex
        ) {
            super.getTableCellRendererComponent(target, value, selected, focused, rowIndex, columnIndex);
            ChangesTableProjector.Row row = ((ChangesTableModel) target.getModel())
                    .rowAt(target.convertRowIndexToModel(rowIndex));
            setHorizontalAlignment(SwingConstants.LEFT);
            setIcon(null);
            if (row.isGroup()) {
                setText("");
                setToolTipText(null);
            } else {
                String fullPath = row.getPathValue();
                int availableWidth = target.getCellRect(rowIndex, columnIndex, true).width
                        - getInsets().left - getInsets().right - JBUI.scale(8);
                setText(ellipsize(fullPath, getFontMetrics(getFont()), availableWidth));
                setToolTipText(row.item().getPath());
            }
            return this;
        }
    }

    private static final class ChangesTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Name", "Type", "Path"};
        private List<ChangesTableProjector.Row> rows = List.of();

        void setRows(List<ChangesTableProjector.Row> rows) {
            this.rows = List.copyOf(rows);
            fireTableDataChanged();
        }

        ChangesTableProjector.Row rowAt(int index) {
            return rows.get(index);
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
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ChangesTableProjector.Row row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.getNameValue();
                case 1 -> row.getTypeValue();
                case 2 -> row.getPathValue();
                default -> "";
            };
        }
    }
}
