package tennisdb;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Точка входа: интерактивная консоль и выполнение UTF-8 SQL-скрипта. */
public final class Main {
    private Main() { }
    public static void main(String[] args) {
        try { run(args); }
        catch (IOException | DbException ex) { System.err.println("Ошибка: " + ex.getMessage()); System.exit(1); }
    }
    private static void run(String[] args) throws IOException {
        Path directory = Paths.get("data"), script = null;
        boolean empty = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data":
                    if (++i >= args.length) throw new DbException("После --data нужен каталог");
                    directory = Paths.get(args[i]); break;
                case "--script":
                    if (++i >= args.length) throw new DbException("После --script нужен файл");
                    script = Paths.get(args[i]); break;
                case "--empty": empty = true; break;
                case "--help": help(new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true)); return;
                default: throw new DbException("Неизвестный аргумент: " + args[i]);
            }
        }
        PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        try (FileRepository repository = new FileRepository(directory)) {
            Engine engine = new Engine(repository);
            if (repository.recoveryMessage() != null) out.println(repository.recoveryMessage());
            if (repository.isFresh() && !empty) { Demo.initialize(engine); out.println("Создана демонстрационная база теннисного центра."); }
            if (script != null) {
                String text = new String(Files.readAllBytes(script), StandardCharsets.UTF_8);
                Sql.Split split = Sql.split(text);
                for (String sql : split.complete) execute(engine, sql, out);
                if (Sql.hasCode(split.remainder)) execute(engine, split.remainder, out);
                return;
            }
            out.println("TennisMiniDB | .help — справка | SQL заканчивается точкой с запятой.");
            out.println("Данные: " + directory.toAbsolutePath().normalize());
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String pending = "";
            while (true) {
                out.print(pending.trim().isEmpty() ? "sql> " : " ... "); out.flush();
                String line = input.readLine();
                if (line == null) {
                    if (!pending.trim().isEmpty()) out.println("Незавершённая команда не выполнена: требуется ;");
                    break;
                }
                if (line.trim().equalsIgnoreCase(".cancel")) { pending = ""; continue; }
                if (pending.trim().isEmpty() && line.trim().startsWith(".")) {
                    String meta = line.trim();
                    if (meta.equalsIgnoreCase(".exit")) break;
                    if (meta.equalsIgnoreCase(".help")) help(out);
                    else if (meta.equalsIgnoreCase(".tables")) out.println(engine.tableNames());
                    else if (meta.toLowerCase(java.util.Locale.ROOT).startsWith(".schema ")) {
                        try { for (Column c : engine.schema(meta.substring(8).trim().toLowerCase(java.util.Locale.ROOT))) out.println(c); }
                        catch (DbException ex) { out.println("Ошибка: " + ex.getMessage()); }
                    } else out.println("Неизвестная служебная команда. См. .help");
                    continue;
                }
                Sql.Split split = Sql.split(pending + line + "\n");
                pending = split.remainder;
                for (String sql : split.complete) {
                    try { execute(engine, sql, out); }
                    catch (IOException | DbException ex) { out.println("Ошибка: " + ex.getMessage()); }
                }
                // Комментарии сами по себе не должны блокировать служебные команды.
                try { if (!Sql.hasCode(pending)) pending = ""; }
                catch (DbException ignored) { /* Незакрытая строка может продолжаться на следующей строке. */ }
            }
        }
    }
    private static void execute(Engine engine, String sql, PrintWriter out) throws IOException {
        if (Sql.hasCode(sql)) TablePrinter.print(engine.execute(sql), out);
    }
    private static void help(PrintWriter out) {
        out.println("Запуск: java -jar build/tennis-minidb.jar [--data каталог] [--empty] [--script файл.sql]");
        out.println(".tables | .schema имя_таблицы | .cancel | .exit");
        out.println("INSERT, SELECT, UPDATE, DELETE, CREATE/DROP TABLE, CREATE/DROP INDEX, EXPLAIN SELECT.");
        out.println("WHERE: = != <> < <= > >=, AND, IS NULL, IS NOT NULL. Строки и даты — в одинарных кавычках.");
    }
}
