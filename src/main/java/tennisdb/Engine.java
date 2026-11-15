package tennisdb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Движок SQL. Каждая изменяющая команда готовит отдельное состояние:
 * проверка целостности → сохранение → публикация в памяти.
 * Ошибка команды не оставляет частично изменённые записи или индексы.
 */
public final class Engine {
    private final Repository repository;
    private Database state;
    public Engine(Repository repository) throws IOException {
        this.repository = repository;
        state = repository.load();
        state.validate();
    }
    public List<String> tableNames() { return new ArrayList<>(state.tables.keySet()); }
    public List<Column> schema(String table) { return state.table(table).columns; }
    Database state() { return state; }
    public QueryResult execute(String sql) throws IOException { return execute(Sql.parse(sql), false); }
    /** forceScan нужен для воспроизводимого сравнения одного запроса с индексом и без него. */
    public QueryResult execute(Sql.Command command, boolean forceScan) throws IOException {
        if (command.kind == Sql.Kind.SELECT) return select(command, forceScan);
        Database next = state.copy();
        Table changed = null;
        int affected = 0;
        switch (command.kind) {
            case CREATE_TABLE:
                if (next.tables.containsKey(command.table)) throw new DbException("Таблица уже существует: " + command.table);
                changed = new Table(command.table, command.columns);
                next.tables.put(command.table, changed);
                break;
            case DROP_TABLE:
                next.table(command.table);
                next.tables.remove(command.table);
                break;
            case DROP_INDEX:
                Table owner = null;
                for (Table t : next.tables.values()) if (t.indexes.containsKey(command.index)) { owner = t; break; }
                if (owner == null) throw new DbException("Индекс не найден: " + command.index);
                changed = owner.copyForWrite(); changed.indexes.remove(command.index);
                next.tables.put(changed.name, changed);
                break;
            default:
                changed = next.table(command.table).copyForWrite();
                next.tables.put(changed.name, changed);
                if (command.kind == Sql.Kind.CREATE_INDEX) {
                    for (Table t : next.tables.values())
                        if (t.indexes.containsKey(command.index)) throw new DbException("Индекс уже существует: " + command.index);
                    changed.position(command.indexColumn);
                    changed.indexes.put(command.index, new AvlIndex(command.index, command.indexColumn));
                } else if (command.kind == Sql.Kind.INSERT) {
                    insert(changed, command); affected = command.values.size();
                } else if (command.kind == Sql.Kind.UPDATE) {
                    affected = update(changed, command);
                } else if (command.kind == Sql.Kind.DELETE) {
                    List<BoundPredicate> predicates = bind(changed, command.predicates);
                    List<Object> remove = new ArrayList<>();
                    for (Row row : changed) if (matches(row, predicates)) remove.add(changed.primaryKey(row));
                    for (Object id : remove) changed.rows.remove(id);
                    affected = remove.size();
                }
                break;
        }
        next.validate();
        if (changed != null) changed.rebuildIndexes();
        repository.save(next);
        state = next;
        switch (command.kind) {
            case INSERT: return QueryResult.message("Добавлено записей: " + affected);
            case UPDATE: return QueryResult.message("Изменено записей: " + affected);
            case DELETE: return QueryResult.message("Удалено записей: " + affected);
            default: return QueryResult.message("Выполнено: " + command.kind);
        }
    }
    private static void insert(Table table, Sql.Command command) {
        List<String> names = command.names;
        if (names.isEmpty()) {
            names = new ArrayList<>();
            for (Column c : table.columns) names.add(c.name);
        }
        Set<String> seen = new HashSet<>();
        List<Integer> positions = new ArrayList<>();
        for (String name : names) {
            if (!seen.add(name)) throw new DbException("Повтор столбца: " + name);
            positions.add(table.position(name));
        }
        for (List<Sql.Literal> input : command.values) {
            if (input.size() != names.size()) throw new DbException("Число значений не совпадает с числом столбцов");
            List<Object> values = new ArrayList<>(Collections.nCopies(table.columns.size(), null));
            for (int i = 0; i < input.size(); i++) {
                int pos = positions.get(i);
                values.set(pos, table.columns.get(pos).type.convert(input.get(i)));
            }
            table.add(new Row(values));
        }
    }
    private static int update(Table table, Sql.Command command) {
        List<BoundPredicate> predicates = bind(table, command.predicates);
        Map<Integer, Object> assignments = new LinkedHashMap<>();
        for (Map.Entry<String, Sql.Literal> entry : command.assignments.entrySet()) {
            int pos = table.position(entry.getKey());
            assignments.put(pos, table.columns.get(pos).type.convert(entry.getValue()));
        }
        List<Row> rows = new ArrayList<>();
        int affected = 0;
        for (Row row : table) {
            if (!matches(row, predicates)) { rows.add(row); continue; }
            List<Object> values = new ArrayList<>(row.values());
            for (Map.Entry<Integer, Object> a : assignments.entrySet()) values.set(a.getKey(), a.getValue());
            rows.add(new Row(values)); affected++;
        }
        table.rows.clear();
        for (Row row : rows) table.add(row);
        return affected;
    }
    private static final class BoundPredicate {
        final int position;
        final String column, operator;
        final Object value;
        BoundPredicate(int position, String column, String operator, Object value) {
            this.position = position; this.column = column; this.operator = operator; this.value = value;
        }
        boolean test(Row row) {
            Object actual = row.get(position);
            if (operator.equals("IS NULL")) return actual == null;
            if (operator.equals("IS NOT NULL")) return actual != null;
            if (actual == null || value == null) return false;
            int c = DataType.compare(actual, value);
            switch (operator) {
                case "=": return c == 0;
                case "!=": case "<>": return c != 0;
                case "<": return c < 0;
                case "<=": return c <= 0;
                case ">": return c > 0;
                case ">=": return c >= 0;
                default: throw new DbException("Неизвестное сравнение: " + operator);
            }
        }
    }
    private static List<BoundPredicate> bind(Table table, List<Sql.Predicate> predicates) {
        List<BoundPredicate> result = new ArrayList<>();
        for (Sql.Predicate p : predicates) {
            int pos = table.position(p.column);
            result.add(new BoundPredicate(pos, p.column, p.operator, table.columns.get(pos).type.convert(p.value)));
        }
        return result;
    }
    private static boolean matches(Row row, List<BoundPredicate> predicates) {
        for (BoundPredicate p : predicates) if (!p.test(row)) return false;
        return true;
    }
    private QueryResult select(Sql.Command command, boolean forceScan) {
        Table table = state.table(command.table);
        List<BoundPredicate> predicates = bind(table, command.predicates);
        QueryResult result = new QueryResult();
        result.explain = command.explain;
        List<Integer> projection = new ArrayList<>();
        if (command.names.isEmpty()) {
            for (int i = 0; i < table.columns.size(); i++) {
                projection.add(i); result.columns.add(table.columns.get(i).name);
            }
        } else for (String name : command.names) { projection.add(table.position(name)); result.columns.add(name); }
        List<Object> candidateIds = null;
        result.plan = "FULL SCAN";
        if (!forceScan) {
            for (BoundPredicate p : predicates) {
                if (p.column.equals(table.primaryColumn().name) && p.operator.equals("=") && p.value != null) {
                    candidateIds = table.rows.containsKey(p.value) ? Collections.singletonList(p.value) : Collections.emptyList();
                    result.plan = "PRIMARY KEY";
                    break;
                }
                for (AvlIndex index : table.indexes.values()) {
                    if (!index.column.equals(p.column) || !index.supports(p.operator, p.value)) continue;
                    List<Object> ids = index.find(p.operator, p.value);
                    if (candidateIds == null || ids.size() < candidateIds.size()) {
                        candidateIds = ids; result.plan = "INDEX " + index.name + " (AVL)";
                    }
                }
            }
        }
        if (candidateIds == null) {
            for (Row row : table) addResult(row, predicates, projection, result);
        } else {
            for (Object id : candidateIds) addResult(table.rows.get(id), predicates, projection, result);
        }
        return result;
    }
    private static void addResult(Row row, List<BoundPredicate> predicates, List<Integer> projection, QueryResult result) {
        if (row == null) throw new IllegalStateException("Индекс ссылается на отсутствующую запись");
        result.examined++;
        if (!matches(row, predicates)) return;
        List<Object> values = new ArrayList<>();
        for (int pos : projection) values.add(row.get(pos));
        result.rows.add(values);
    }
}
