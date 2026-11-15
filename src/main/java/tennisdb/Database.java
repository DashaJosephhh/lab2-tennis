package tennisdb;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/** Каталог таблиц и проверка целостности всего состояния перед фиксацией. */
public final class Database implements Serializable {
    private static final long serialVersionUID = 1L;
    final Map<String, Table> tables = new LinkedHashMap<>();
    public Table table(String name) {
        Table t = tables.get(name);
        if (t == null) throw new DbException("Таблица не найдена: " + name);
        return t;
    }
    public Database copy() {
        Database d = new Database();
        d.tables.putAll(tables);
        return d;
    }
    /** Внешний ключ ссылается на PK того же типа; удаление/изменение родителя — RESTRICT. */
    public void validate() {
        for (Table t : tables.values()) {
            for (Column c : t.columns) {
                if (c.referenceTable == null) continue;
                Table parent = table(c.referenceTable);
                Column target = parent.columns.get(parent.position(c.referenceColumn));
                if (!target.primaryKey || c.type != target.type)
                    throw new DbException("REFERENCES требует PRIMARY KEY того же типа: " + c.name);
            }
            for (Map.Entry<Object, Row> entry : t.rows.entrySet()) {
                Row row = entry.getValue();
                if (row.values().size() != t.columns.size()
                        || !entry.getKey().equals(t.primaryKey(row)))
                    throw new DbException("Нарушена структура таблицы " + t.name);
                for (int i = 0; i < t.columns.size(); i++) {
                    Column c = t.columns.get(i);
                    Object value = row.get(i);
                    if ((c.notNull && value == null) || !c.type.accepts(value))
                        throw new DbException("Некорректное значение " + t.name + "." + c.name);
                    if (value != null && c.referenceTable != null
                            && !table(c.referenceTable).rows.containsKey(value))
                        throw new DbException("Нарушен внешний ключ " + t.name + "." + c.name + " = " + value);
                }
            }
        }
    }
}
