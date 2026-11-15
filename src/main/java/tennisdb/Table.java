package tennisdb;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Таблица, каталог её индексов и Iterator для последовательного просмотра. */
public final class Table implements Serializable, Iterable<Row> {
    private static final long serialVersionUID = 1L;
    public final String name;
    public final List<Column> columns;
    final Map<Object, Row> rows = new LinkedHashMap<>();
    final Map<String, AvlIndex> indexes = new LinkedHashMap<>();
    private final int primaryPosition;

    public Table(String name, List<Column> columns) {
        this.name = name;
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
        int pk = -1;
        for (int i = 0; i < columns.size(); i++) {
            for (int j = 0; j < i; j++)
                if (columns.get(i).name.equals(columns.get(j).name))
                    throw new DbException("Повтор столбца: " + columns.get(i).name);
            if (columns.get(i).primaryKey) {
                if (pk != -1) throw new DbException("Разрешён ровно один PRIMARY KEY");
                pk = i;
            }
        }
        if (pk < 0) throw new DbException("В таблице необходим PRIMARY KEY");
        primaryPosition = pk;
    }
    public int position(String column) {
        for (int i = 0; i < columns.size(); i++) if (columns.get(i).name.equals(column)) return i;
        throw new DbException("В таблице " + name + " нет столбца " + column);
    }
    public Column primaryColumn() { return columns.get(primaryPosition); }
    public Object primaryKey(Row row) { return row.get(primaryPosition); }
    public int size() { return rows.size(); }
    public void add(Row row) {
        Object key = primaryKey(row);
        if (key == null) throw new DbException("PRIMARY KEY не может быть NULL");
        if (rows.containsKey(key)) throw new DbException("Повтор PRIMARY KEY: " + key);
        rows.put(key, row);
    }
    public Table copyForWrite() {
        Table copy = new Table(name, columns);
        copy.rows.putAll(rows);
        for (AvlIndex index : indexes.values())
            copy.indexes.put(index.name, new AvlIndex(index.name, index.column));
        return copy;
    }
    public void rebuildIndexes() {
        for (AvlIndex definition : new ArrayList<>(indexes.values())) {
            AvlIndex index = new AvlIndex(definition.name, definition.column);
            int pos = position(index.column);
            for (Row row : rows.values()) index.add(row.get(pos), primaryKey(row));
            indexes.put(index.name, index);
        }
    }
    @Override public Iterator<Row> iterator() {
        return Collections.unmodifiableCollection(rows.values()).iterator();
    }
}
