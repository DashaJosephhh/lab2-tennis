package tennisdb;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/** Выводит SELECT в табличном виде, экранируя переводы строк внутри ячеек. */
public final class TablePrinter {
    private TablePrinter() { }
    public static void print(QueryResult result, PrintWriter out) {
        if (result.message != null) { out.println(result.message); return; }
        if (!result.explain) {
            List<List<String>> lines = new ArrayList<>();
            lines.add(result.columns);
            for (List<Object> row : result.rows) {
                List<String> values = new ArrayList<>();
                for (Object value : row) values.add(value == null ? "NULL" : value.toString()
                        .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t"));
                lines.add(values);
            }
            int[] widths = new int[result.columns.size()];
            for (List<String> line : lines)
                for (int i = 0; i < widths.length; i++) widths[i] = Math.max(widths[i], line.get(i).length());
            border(widths, out);
            for (int n = 0; n < lines.size(); n++) {
                out.print("|");
                for (int i = 0; i < widths.length; i++) {
                    String value = lines.get(n).get(i);
                    out.print(" " + value);
                    for (int j = value.length(); j < widths[i]; j++) out.print(' ');
                    out.print(" |");
                }
                out.println(); if (n == 0) border(widths, out);
            }
            border(widths, out);
        }
        out.println("Строк: " + result.rows.size() + "; план: " + result.plan + "; проверено записей: " + result.examined);
    }
    private static void border(int[] widths, PrintWriter out) {
        out.print('+');
        for (int width : widths) { for (int i = 0; i < width + 2; i++) out.print('-'); out.print('+'); }
        out.println();
    }
}
