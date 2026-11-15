package tennisdb;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Поддерживаемые типы и строгое преобразование SQL-литералов. */
public enum DataType {
    INT, DOUBLE, STRING, DATE, BOOLEAN;

    public static DataType fromSql(String name) {
        String s = name.toUpperCase(Locale.ROOT);
        if (s.equals("INTEGER")) return INT;
        if (s.equals("FLOAT")) return DOUBLE;
        if (s.equals("VARCHAR") || s.equals("TEXT")) return STRING;
        if (s.equals("BOOL")) return BOOLEAN;
        try { return valueOf(s); }
        catch (IllegalArgumentException ex) { throw new DbException("Неизвестный тип: " + name); }
    }

    public Object convert(Sql.Literal value) {
        if (value.kind == Sql.TokenKind.WORD && value.text.equalsIgnoreCase("NULL")) return null;
        try {
            switch (this) {
                case INT:
                    if (value.kind == Sql.TokenKind.NUMBER && value.text.matches("[+-]?\\d+"))
                        return Integer.valueOf(value.text);
                    break;
                case DOUBLE:
                    if (value.kind == Sql.TokenKind.NUMBER) {
                        double n = Double.parseDouble(value.text);
                        if (!Double.isFinite(n)) break;
                        return n == 0.0 ? 0.0 : n;
                    }
                    break;
                case STRING:
                    if (value.kind == Sql.TokenKind.STRING) return value.text;
                    break;
                case DATE:
                    if (value.kind == Sql.TokenKind.STRING && value.text.matches("\\d{4}-\\d{2}-\\d{2}"))
                        return LocalDate.parse(value.text);
                    break;
                case BOOLEAN:
                    if (value.kind == Sql.TokenKind.WORD &&
                            (value.text.equalsIgnoreCase("TRUE") || value.text.equalsIgnoreCase("FALSE")))
                        return Boolean.valueOf(value.text);
                    break;
                default: break;
            }
        } catch (NumberFormatException | DateTimeParseException ex) {
            throw new DbException("Значение '" + value.text + "' не соответствует типу " + this);
        }
        throw new DbException("Значение '" + value.text + "' не соответствует типу " + this);
    }

    public boolean accepts(Object value) {
        if (value == null) return true;
        switch (this) {
            case INT: return value instanceof Integer;
            case DOUBLE: return value instanceof Double && Double.isFinite((Double) value);
            case STRING: return value instanceof String;
            case DATE: return value instanceof LocalDate;
            case BOOLEAN: return value instanceof Boolean;
            default: return false;
        }
    }

    @SuppressWarnings("unchecked")
    public static int compare(Object a, Object b) {
        return ((Comparable<Object>) a).compareTo(b);
    }
}
