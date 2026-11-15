package tennisdb;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Собственный вторичный индекс на AVL-дереве.
 * Узел хранит значение столбца и множество первичных ключей записей.
 * Дубликаты допустимы, NULL не индексируется. Дерево сериализуется целиком.
 */
public final class AvlIndex implements Serializable {
    private static final long serialVersionUID = 1L;
    public final String name;
    public final String column;
    private Node root;

    private static final class Node implements Serializable {
        private static final long serialVersionUID = 1L;
        final Object key;
        final Set<Object> ids = new LinkedHashSet<>();
        Node left, right;
        int height = 1;
        Node(Object key, Object id) { this.key = key; ids.add(id); }
    }

    public AvlIndex(String name, String column) { this.name = name; this.column = column; }
    public void add(Object key, Object primaryKey) {
        if (key != null) root = insert(root, key, primaryKey);
    }
    public int height() { return height(root); }
    private static int height(Node n) { return n == null ? 0 : n.height; }
    private static void refresh(Node n) { n.height = 1 + Math.max(height(n.left), height(n.right)); }
    private static Node rotateRight(Node y) {
        Node x = y.left;
        y.left = x.right;
        x.right = y;
        refresh(y); refresh(x);
        return x;
    }
    private static Node rotateLeft(Node x) {
        Node y = x.right;
        x.right = y.left;
        y.left = x;
        refresh(x); refresh(y);
        return y;
    }
    private static Node insert(Node n, Object key, Object id) {
        if (n == null) return new Node(key, id);
        int c = DataType.compare(key, n.key);
        if (c == 0) { n.ids.add(id); return n; }
        if (c < 0) n.left = insert(n.left, key, id);
        else n.right = insert(n.right, key, id);
        refresh(n);
        int balance = height(n.left) - height(n.right);
        if (balance > 1) {
            if (DataType.compare(key, n.left.key) > 0) n.left = rotateLeft(n.left);
            return rotateRight(n);
        }
        if (balance < -1) {
            if (DataType.compare(key, n.right.key) < 0) n.right = rotateRight(n.right);
            return rotateLeft(n);
        }
        return n;
    }

    public boolean supports(String operator, Object value) {
        return value != null && (operator.equals("=") || operator.equals("<") || operator.equals("<=")
                || operator.equals(">") || operator.equals(">="));
    }

    /** Возвращает только подходящие первичные ключи; ветви вне диапазона отсекаются. */
    public List<Object> find(String operator, Object value) {
        if (!supports(operator, value)) throw new DbException("Условие не поддерживается индексом");
        List<Object> ids = new ArrayList<>();
        collect(root, operator, value, ids);
        return ids;
    }
    private static void collect(Node n, String op, Object value, List<Object> out) {
        if (n == null) return;
        int c = DataType.compare(n.key, value);
        if (op.equals("=")) {
            if (c == 0) out.addAll(n.ids);
            else collect(c < 0 ? n.right : n.left, op, value, out);
        } else if (op.equals("<") || op.equals("<=")) {
            collect(n.left, op, value, out);
            if (c < 0 || (c == 0 && op.equals("<="))) {
                out.addAll(n.ids);
                collect(n.right, op, value, out);
            }
        } else {
            if (c > 0 || (c == 0 && op.equals(">="))) {
                collect(n.left, op, value, out);
                out.addAll(n.ids);
            }
            collect(n.right, op, value, out);
        }
    }

    /** Проверяет порядок ключей, высоты и баланс дерева (используется при тестировании). */
    public void checkInvariant() { check(root, null, null); }
    private static int check(Node n, Object min, Object max) {
        if (n == null) return 0;
        if ((min != null && DataType.compare(n.key, min) <= 0)
                || (max != null && DataType.compare(n.key, max) >= 0) || n.ids.isEmpty())
            throw new IllegalStateException("Нарушен порядок AVL");
        int left = check(n.left, min, n.key), right = check(n.right, n.key, max);
        if (Math.abs(left - right) > 1 || n.height != 1 + Math.max(left, right))
            throw new IllegalStateException("Нарушен баланс AVL");
        return n.height;
    }
}
