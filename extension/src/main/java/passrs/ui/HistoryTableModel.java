package passrs.ui;

import javax.swing.table.AbstractTableModel;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class HistoryTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = {"Time", "Method", "URL", "Status", "Final URL", "Title"};
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final List<HistoryEntry> entries = new ArrayList<>();

    @Override
    public int getRowCount() {
        return entries.size();
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
    public Object getValueAt(int rowIndex, int columnIndex) {
        HistoryEntry entry = entries.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> TIME_FORMATTER.format(entry.time());
            case 1 -> entry.method();
            case 2 -> entry.url();
            case 3 -> entry.status();
            case 4 -> entry.finalUrl();
            case 5 -> entry.title();
            default -> "";
        };
    }

    public void addEntry(HistoryEntry entry) {
        int row = entries.size();
        entries.add(entry);
        fireTableRowsInserted(row, row);
    }

    public HistoryEntry entryAt(int index) {
        return entries.get(index);
    }

    public void clear() {
        if (entries.isEmpty()) {
            return;
        }
        entries.clear();
        fireTableDataChanged();
    }
}
