package tennisdb;

import java.io.Serializable;

/** Неизменяемое описание столбца, включая первичный и внешний ключи. */
public final class Column implements Serializable {
    private static final long serialVersionUID = 1L;
    public final String name;
    public final DataType type;
    public final boolean primaryKey;
    public final boolean notNull;
    public final String referenceTable;
    public final String referenceColumn;

    public Column(String name, DataType type, boolean primaryKey, boolean notNull,
                  String referenceTable, String referenceColumn) {
        this.name = name;
        this.type = type;
        this.primaryKey = primaryKey;
        this.notNull = notNull || primaryKey;
        this.referenceTable = referenceTable;
        this.referenceColumn = referenceColumn;
    }

    @Override public String toString() {
        return name + " " + type + (primaryKey ? " PRIMARY KEY" : notNull ? " NOT NULL" : "")
                + (referenceTable == null ? "" : " REFERENCES " + referenceTable + "(" + referenceColumn + ")");
    }
}
