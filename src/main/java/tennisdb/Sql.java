package tennisdb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Лексер и рекурсивный парсер небольшого, явно ограниченного подмножества SQL. */
public final class Sql {
    private Sql() { }
    public enum TokenKind { WORD, STRING, NUMBER, SYMBOL, END }
    public enum Kind { CREATE_TABLE, DROP_TABLE, CREATE_INDEX, DROP_INDEX, INSERT, SELECT, UPDATE, DELETE }

    public static final class Literal {
        public final TokenKind kind;
        public final String text;
        public Literal(TokenKind kind, String text) { this.kind = kind; this.text = text; }
    }
    public static final class Predicate {
        public final String column, operator;
        public final Literal value;
        Predicate(String column, String operator, Literal value) {
            this.column = column; this.operator = operator; this.value = value;
        }
    }
    /** Command: результат разбора, который не выполняет операции с данными. */
    public static final class Command {
        Kind kind;
        String table, index, indexColumn;
        boolean explain;
        final List<Column> columns = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final List<List<Literal>> values = new ArrayList<>();
        final Map<String, Literal> assignments = new LinkedHashMap<>();
        final List<Predicate> predicates = new ArrayList<>();
    }
    private static final class Token {
        final TokenKind kind;
        final String text;
        final int offset;
        Token(TokenKind kind, String text, int offset) {
            this.kind = kind; this.text = text; this.offset = offset;
        }
    }
    private static final class Lexer {
        private final String text;
        private int pos;
        Lexer(String text) { this.text = text; }
        Token next() {
            while (pos < text.length()) {
                if (Character.isWhitespace(text.charAt(pos)) || text.charAt(pos) == '\ufeff') { pos++; continue; }
                if (text.startsWith("--", pos)) {
                    while (pos < text.length() && text.charAt(pos) != '\n') pos++;
                    continue;
                }
                break;
            }
            int start = pos;
            if (pos == text.length()) return new Token(TokenKind.END, "<конец>", pos);
            char c = text.charAt(pos++);
            if (c == '\'') {
                StringBuilder value = new StringBuilder();
                while (pos < text.length()) {
                    char q = text.charAt(pos++);
                    if (q == '\'') {
                        if (pos < text.length() && text.charAt(pos) == '\'') { pos++; value.append('\''); }
                        else return new Token(TokenKind.STRING, value.toString(), start);
                    } else value.append(q);
                }
                throw new DbException("Незакрытая строка, позиция " + (start + 1));
            }
            if (letter(c)) {
                while (pos < text.length() && (letter(text.charAt(pos)) || Character.isDigit(text.charAt(pos)))) pos++;
                return new Token(TokenKind.WORD, text.substring(start, pos), start);
            }
            if (Character.isDigit(c) || ((c == '+' || c == '-') && pos < text.length()
                    && Character.isDigit(text.charAt(pos)))) {
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
                if (pos < text.length() && text.charAt(pos) == '.') {
                    pos++;
                    while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
                }
                if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                    pos++;
                    if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
                    while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
                }
                return new Token(TokenKind.NUMBER, text.substring(start, pos), start);
            }
            if ("(),;*=<>!".indexOf(c) >= 0) {
                if (pos < text.length() && (("<>!".indexOf(c) >= 0 && text.charAt(pos) == '=')
                        || (c == '<' && text.charAt(pos) == '>'))) pos++;
                return new Token(TokenKind.SYMBOL, text.substring(start, pos), start);
            }
            throw new DbException("Неожиданный символ '" + c + "', позиция " + (start + 1));
        }
        private static boolean letter(char c) { return c == '_' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'; }
    }

    public static Command parse(String sql) { return new Parser(sql).parse(); }
    public static boolean hasCode(String sql) { return new Lexer(sql).next().kind != TokenKind.END; }

    private static final class Parser {
        private final Lexer lexer;
        private Token token;
        Parser(String text) { lexer = new Lexer(text); token = lexer.next(); }
        private boolean accept(String s) {
            if (token.kind != TokenKind.STRING && token.text.equalsIgnoreCase(s)) { token = lexer.next(); return true; }
            return false;
        }
        private void expect(String s) { if (!accept(s)) fail("Ожидалось " + s); }
        private void fail(String s) { throw new DbException(s + ", получено '" + token.text + "', позиция " + (token.offset + 1)); }
        private String identifier() {
            if (token.kind != TokenKind.WORD) fail("Ожидалось имя таблицы/столбца");
            String name = token.text.toLowerCase(Locale.ROOT);
            token = lexer.next();
            return name;
        }
        private Literal literal() {
            if (accept("DATE")) {
                if (token.kind != TokenKind.STRING) fail("После DATE ожидается 'YYYY-MM-DD'");
            }
            if (token.kind != TokenKind.STRING && token.kind != TokenKind.NUMBER
                    && !(token.kind == TokenKind.WORD && (token.text.equalsIgnoreCase("NULL")
                    || token.text.equalsIgnoreCase("TRUE") || token.text.equalsIgnoreCase("FALSE"))))
                fail("Ожидался литерал");
            Literal result = new Literal(token.kind, token.text);
            token = lexer.next();
            return result;
        }
        Command parse() {
            Command c = new Command();
            c.explain = accept("EXPLAIN");
            if (accept("CREATE")) {
                if (accept("TABLE")) {
                    c.kind = Kind.CREATE_TABLE; c.table = identifier(); expect("(");
                    do {
                        String name = identifier();
                        DataType type = DataType.fromSql(identifier());
                        boolean pk = false, notNull = false;
                        String refTable = null, refColumn = null;
                        boolean more = true;
                        while (more) {
                            if (accept("PRIMARY")) {
                                if (pk) fail("Повтор PRIMARY KEY");
                                expect("KEY"); pk = true;
                            } else if (accept("NOT")) {
                                if (notNull) fail("Повтор NOT NULL");
                                expect("NULL"); notNull = true;
                            } else if (accept("REFERENCES")) {
                                if (refTable != null) fail("Повтор REFERENCES");
                                refTable = identifier(); expect("("); refColumn = identifier(); expect(")");
                            } else more = false;
                        }
                        c.columns.add(new Column(name, type, pk, notNull, refTable, refColumn));
                    } while (accept(","));
                    expect(")");
                } else {
                    expect("INDEX"); c.kind = Kind.CREATE_INDEX; c.index = identifier();
                    expect("ON"); c.table = identifier(); expect("("); c.indexColumn = identifier(); expect(")");
                }
            } else if (accept("DROP")) {
                if (accept("TABLE")) { c.kind = Kind.DROP_TABLE; c.table = identifier(); }
                else { expect("INDEX"); c.kind = Kind.DROP_INDEX; c.index = identifier(); }
            } else if (accept("INSERT")) {
                c.kind = Kind.INSERT; expect("INTO"); c.table = identifier();
                if (accept("(")) {
                    do { c.names.add(identifier()); } while (accept(","));
                    expect(")");
                }
                expect("VALUES");
                do {
                    expect("("); List<Literal> row = new ArrayList<>();
                    do { row.add(literal()); } while (accept(","));
                    expect(")"); c.values.add(row);
                } while (accept(","));
            } else if (accept("SELECT")) {
                c.kind = Kind.SELECT;
                if (!accept("*")) do { c.names.add(identifier()); } while (accept(","));
                expect("FROM"); c.table = identifier(); where(c);
            } else if (accept("UPDATE")) {
                c.kind = Kind.UPDATE; c.table = identifier(); expect("SET");
                do {
                    String column = identifier(); expect("=");
                    if (c.assignments.put(column, literal()) != null) fail("Повтор столбца в SET");
                } while (accept(","));
                where(c);
            } else if (accept("DELETE")) {
                c.kind = Kind.DELETE; expect("FROM"); c.table = identifier(); where(c);
            } else fail("Ожидалась SQL-команда");
            if (c.explain && c.kind != Kind.SELECT) fail("EXPLAIN поддерживается только для SELECT");
            accept(";");
            if (token.kind != TokenKind.END) fail("Лишний текст или неподдерживаемая конструкция");
            return c;
        }
        private void where(Command c) {
            if (!accept("WHERE")) return;
            do {
                String column = identifier();
                String operator;
                Literal value;
                if (accept("IS")) {
                    operator = accept("NOT") ? "IS NOT NULL" : "IS NULL";
                    expect("NULL"); value = new Literal(TokenKind.WORD, "NULL");
                } else {
                    operator = token.text;
                    if (!(operator.equals("=") || operator.equals("<>") || operator.equals("!=")
                            || operator.equals("<") || operator.equals("<=") || operator.equals(">") || operator.equals(">=")))
                        fail("Ожидался оператор сравнения");
                    token = lexer.next(); value = literal();
                }
                c.predicates.add(new Predicate(column, operator, value));
            } while (accept("AND"));
        }
    }

    public static final class Split {
        public final List<String> complete = new ArrayList<>();
        public String remainder;
    }
    /** Делит скрипт по ; вне строк и -- комментариев; сохраняет незавершённый хвост для консоли. */
    public static Split split(String text) {
        Split split = new Split();
        boolean quoted = false, comment = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (comment) { if (c == '\n') comment = false; continue; }
            if (quoted) {
                if (c == '\'') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\'') i++;
                    else quoted = false;
                }
            } else if (c == '\'') quoted = true;
            else if (c == '-' && i + 1 < text.length() && text.charAt(i + 1) == '-') { comment = true; i++; }
            else if (c == ';') { split.complete.add(text.substring(start, i + 1)); start = i + 1; }
        }
        split.remainder = text.substring(start);
        return split;
    }
}
