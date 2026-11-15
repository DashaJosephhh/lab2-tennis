package tennisdb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Независимые проверки поведения SQL, хранения и индекса. Запуск без JUnit. */
public final class DatabaseTest {
    private static int assertions;
    private static int groups;
    private interface Checked { void run() throws Exception; }
    private static final class MemoryRepository implements Repository {
        Database value = new Database();
        boolean failSave;
        public Database load() { return value; }
        public void save(Database database) throws IOException {
            if (failSave) throw new IOException("Искусственная ошибка записи");
            value = database;
        }
        public void close() { }
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Checked action, String message) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (DbException | IOException ex) { rejected = true; }
        check(rejected, message);
    }
    private static void group(String name, Checked action) throws Exception {
        action.run(); groups++; System.out.println("PASS " + name);
    }
    private static Engine engine() throws IOException { return new Engine(new MemoryRepository()); }
    private static Engine demo() throws IOException { Engine e = engine(); Demo.initialize(e); return e; }
    private static Set<List<Object>> rows(QueryResult q) { return new HashSet<>(q.rows); }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Paths.get("build", "test-data"));
        group("Пять типов и проекция", () -> {
            Engine e = demo();
            List<Object> r = e.execute("SELECT * FROM bookings WHERE id = 1;").rows.get(0);
            check(r.get(0) instanceof Integer && r.get(3).equals(LocalDate.of(2026, 11, 20)), "INT / DATE");
            check(r.get(6).equals(1500.0) && r.get(7).equals(true), "DOUBLE / BOOLEAN");
            check(e.execute("select full_name from MEMBERS where id=1;").rows.get(0).get(0).equals("Дарья Иванова"), "STRING / регистр");
            check(e.execute("SELECT id, full_name FROM members;").columns.equals(Arrays.asList("id", "full_name")), "Проекция");
        });
        group("Многострочная вставка и уникальность PK", () -> {
            Engine e = demo();
            e.execute("INSERT INTO members (id, full_name) VALUES (3,'O''Brien; -- не комментарий'),(4,'Тест');");
            check(e.execute("SELECT * FROM members;").rows.size() == 4, "Вставка двух строк");
            check(e.execute("SELECT joined FROM members WHERE id=3;").rows.get(0).get(0) == null, "Пропущенный столбец NULL");
            rejects(() -> e.execute("INSERT INTO members VALUES (5,'Новый',NULL),(1,'Дубликат',NULL);"), "Повтор PK");
            check(e.execute("SELECT * FROM members WHERE id=5;").rows.isEmpty(), "Весь INSERT откатился");
            rejects(() -> e.execute("INSERT INTO members VALUES (9,'A',NULL),(9,'B',NULL);"), "Дубликат внутри команды");
            check(e.execute("SELECT * FROM members WHERE id=9;").rows.isEmpty(), "Откат дублей внутри INSERT");
        });
        group("Строгая проверка типов и NOT NULL", () -> {
            Engine e = demo();
            String[] bad = {
                "INSERT INTO members VALUES ('3','Тест',NULL);",
                "INSERT INTO members VALUES (2147483648,'Тест',NULL);",
                "INSERT INTO members VALUES (3,NULL,NULL);",
                "INSERT INTO members VALUES (NULL,'Тест',NULL);",
                "INSERT INTO members VALUES (3,'Тест','2026-02-30');",
                "INSERT INTO courts VALUES (3,'X',1e999,TRUE);",
                "INSERT INTO courts VALUES (3,'X',1.0,'TRUE');",
                "INSERT INTO courts VALUES (3,'X','1.0',TRUE);",
                "INSERT INTO members (id,id) VALUES (3,3);",
                "INSERT INTO members VALUES (3,'Тест');"
            };
            for (String s : bad) rejects(() -> e.execute(s), s);
            check(e.execute("SELECT * FROM members;").rows.size() == 2, "Ошибки не изменили таблицу");
            e.execute("INSERT INTO courts VALUES (3,'X',-0.0,false);");
            check(e.execute("SELECT * FROM courts WHERE hourly_rate=0.0;").rows.size() == 1, "Нормализация -0.0");
        });
        group("Внешние ключи и RESTRICT", () -> {
            Engine e = demo();
            rejects(() -> e.execute("INSERT INTO bookings (id,member_id,court_id,booking_date) VALUES (3,99,1,'2026-11-20');"), "Нет участника");
            rejects(() -> e.execute("UPDATE bookings SET court_id=99 WHERE id=1;"), "UPDATE внешнего ключа");
            rejects(() -> e.execute("DELETE FROM members WHERE id=1;"), "Удаление родителя");
            rejects(() -> e.execute("UPDATE members SET id=10 WHERE id=1;"), "Изменение PK родителя");
            rejects(() -> e.execute("DROP TABLE courts;"), "DROP связанной таблицы");
            e.execute("DELETE FROM bookings WHERE member_id=1;"); e.execute("DELETE FROM members WHERE id=1;");
            check(e.execute("SELECT * FROM members;").rows.size() == 1, "Удаление после дочерних строк");
        });
        group("UPDATE/DELETE, массовые операции и атомарность", () -> {
            Engine e = demo();
            e.execute("UPDATE bookings SET paid=TRUE,total_price=2000 WHERE id>=1;");
            check(e.execute("SELECT * FROM bookings WHERE paid=TRUE AND total_price=2000;").rows.size() == 2, "UPDATE нескольких строк");
            rejects(() -> e.execute("UPDATE bookings SET id=1;"), "Коллизия PK при UPDATE");
            check(e.execute("SELECT * FROM bookings WHERE id=2;").rows.size() == 1, "Откат UPDATE");
            e.execute("DELETE FROM bookings;");
            check(e.execute("SELECT * FROM bookings;").rows.isEmpty(), "DELETE без WHERE");
            e.execute("UPDATE members SET full_name='Новое имя';");
            check(e.execute("SELECT * FROM members WHERE full_name='Новое имя';").rows.size() == 2, "UPDATE без WHERE");
        });
        group("NULL и операторы сравнения", () -> {
            Engine e = demo(); e.execute("INSERT INTO members (id,full_name) VALUES (3,'Тест');");
            check(e.execute("SELECT * FROM members WHERE joined IS NULL;").rows.size() == 1, "IS NULL");
            check(e.execute("SELECT * FROM members WHERE joined IS NOT NULL;").rows.size() == 2, "IS NOT NULL");
            check(e.execute("SELECT * FROM members WHERE joined=NULL;").rows.isEmpty(), "=NULL не истина");
            check(e.execute("SELECT * FROM members WHERE joined<>NULL;").rows.isEmpty(), "<>NULL не истина");
            check(e.execute("SELECT * FROM members WHERE id!=1 AND id<=2;").rows.size() == 1, "AND / != / <=");
            check(e.execute("SELECT * FROM members WHERE joined>DATE '2026-09-01';").rows.size() == 1, "DATE literal");
        });
        group("DDL и ошибки парсера", () -> {
            Engine e = engine();
            rejects(() -> e.execute("CREATE TABLE x (v INT);"), "Нужен PK");
            rejects(() -> e.execute("CREATE TABLE x (id INT PRIMARY KEY, v INT PRIMARY KEY);"), "Два PK");
            rejects(() -> e.execute("CREATE TABLE x (id INT PRIMARY KEY, id STRING);"), "Дубли столбцов");
            rejects(() -> e.execute("CREATE TABLE x (id INT PRIMARY KEY REFERENCES absent(id));"), "Нет родителя");
            e.execute("CREATE TABLE x (id INTEGER PRIMARY KEY, v TEXT);");
            rejects(() -> e.execute("CREATE TABLE y (id INT PRIMARY KEY, x STRING REFERENCES x(id));"), "Тип FK");
            rejects(() -> e.execute("SELECT nope FROM x;"), "Неизвестный столбец в пустой таблице");
            rejects(() -> e.execute("SELECT * FROM x WHERE id='bad';"), "Тип WHERE в пустой таблице");
            rejects(() -> e.execute("SELECT * FROM x WHERE id=1 OR id=2;"), "OR явно отклонён");
            rejects(() -> e.execute("SELECT * FROM x; DELETE FROM x;"), "Лишняя команда");
            rejects(() -> e.execute("INSERT INTO x VALUES (1,'abc);"), "Незакрытая строка");
            e.execute("DROP TABLE x;"); check(e.tableNames().isEmpty(), "DROP TABLE");
        });
        group("Разбиение скриптов и строки", () -> {
            String s = "-- ; комментарий\nINSERT INTO x VALUES (1,'O''Brien; -- текст'); SELECT * FROM x; -- конец";
            Sql.Split split = Sql.split(s);
            check(split.complete.size() == 2 && !Sql.hasCode(split.remainder), "; в кавычках и комментарии");
            Engine e = engine(); e.execute("CREATE TABLE x(id INT PRIMARY KEY,v STRING);");
            e.execute(split.complete.get(0));
            check(e.execute(split.complete.get(1)).rows.get(0).get(1).equals("O'Brien; -- текст"), "Экранирование кавычки");
        });
        group("Индекс: равенство, диапазоны, обновление, удаление", () -> {
            Engine e = demo();
            e.execute("CREATE INDEX idx_price ON bookings(total_price);");
            String[] operators = {"=", "<", "<=", ">", ">=", "!=", "<>"};
            for (String op : operators) {
                Sql.Command c = Sql.parse("SELECT * FROM bookings WHERE total_price" + op + "1500;");
                check(rows(e.execute(c, false)).equals(rows(e.execute(c, true))), "Индекс и scan: " + op);
            }
            check(e.execute("SELECT * FROM bookings WHERE total_price=1500;").plan.contains("idx_price"), "Выбран индекс");
            e.execute("UPDATE bookings SET total_price=777 WHERE id=1;");
            check(e.execute("SELECT * FROM bookings WHERE total_price=1500;").rows.isEmpty(), "Старый ключ исчез");
            check(e.execute("SELECT * FROM bookings WHERE total_price=777;").rows.size() == 1, "Новый ключ появился");
            e.execute("DELETE FROM bookings WHERE total_price=777;");
            check(e.execute("SELECT * FROM bookings WHERE total_price=777;").rows.isEmpty(), "Удалённый ключ исчез");
            e.execute("DROP INDEX idx_price;");
            check(e.execute("SELECT * FROM bookings WHERE total_price=777;").plan.equals("FULL SCAN"), "DROP INDEX");
            rejects(() -> e.execute("CREATE INDEX idx_bookings_date ON courts(name);"), "Уникальность имени индекса");
        });
        group("AVL: случайные ключи, дубликаты, все границы", () -> {
            AvlIndex index = new AvlIndex("test", "value");
            List<Integer> values = new ArrayList<>(); Random random = new Random(42);
            for (int i = 0; i < 2000; i++) { int v = random.nextInt(401)-200; values.add(v); index.add(v, i); index.checkInvariant(); }
            for (int target = -210; target <= 210; target += 7) {
                for (String op : Arrays.asList("=", "<", "<=", ">", ">=")) {
                    Set<Object> expected = new HashSet<>();
                    for (int i = 0; i < values.size(); i++) {
                        int v = values.get(i);
                        if (op.equals("=") ? v==target : op.equals("<") ? v<target : op.equals("<=") ? v<=target
                                : op.equals(">") ? v>target : v>=target) expected.add(i);
                    }
                    List<Object> actual = index.find(op, target);
                    check(new HashSet<>(actual).equals(expected) && actual.size()==expected.size(), "AVL " + op + target);
                }
            }
            check(index.height() < 20, "Логарифмическая высота");
            AvlIndex sorted = new AvlIndex("sorted", "v");
            for (int i=0;i<10000;i++) sorted.add(i,i);
            sorted.checkInvariant(); check(sorted.height()<20, "Баланс при отсортированной вставке");
        });
        group("Индексы всех типов и NULL", () -> {
            Engine e = demo(); e.execute("CREATE INDEX idx_indoor ON courts(indoor);");
            check(e.execute("SELECT * FROM courts WHERE indoor=TRUE;").rows.size()==1, "BOOLEAN индекс");
            check(e.execute("SELECT * FROM members WHERE full_name='Анна Петрова';").plan.contains("INDEX"), "STRING индекс");
            check(e.execute("SELECT * FROM bookings WHERE booking_date>'2026-11-20';").rows.size()==1, "DATE индекс");
            e.execute("INSERT INTO members(id,full_name) VALUES(3,'Тест');");
            e.execute("CREATE INDEX idx_joined ON members(joined);");
            check(e.execute("SELECT * FROM members WHERE joined IS NULL;").rows.size()==1, "NULL исключён из дерева, scan корректен");
        });
        group("Ошибка сохранения не публикует изменение", () -> {
            MemoryRepository repo = new MemoryRepository(); Engine e = new Engine(repo); Demo.initialize(e);
            repo.failSave = true;
            rejects(() -> e.execute("DELETE FROM bookings;"), "Сбой записи");
            check(e.execute("SELECT * FROM bookings;").rows.size()==2, "Состояние памяти сохранено");
        });
        group("Сериализация, рестарт, индекс на диске", () -> {
            Path dir = Files.createTempDirectory(Paths.get("build/test-data"), "restart-");
            try (FileRepository repo = new FileRepository(dir)) {
                Engine e = new Engine(repo); Demo.initialize(e);
                e.execute("UPDATE bookings SET total_price=999 WHERE id=1;");
            }
            try (FileRepository repo = new FileRepository(dir)) {
                Engine e = new Engine(repo);
                QueryResult q=e.execute("SELECT * FROM bookings WHERE booking_date='2026-11-20';");
                check(q.plan.contains("idx_bookings_date") && q.rows.get(0).get(6).equals(999.0), "Индекс и данные восстановлены");
                e.state().table("bookings").indexes.get("idx_bookings_date").checkInvariant();
            }
        });
        group("Повреждённый основной файл и сохранение после восстановления", () -> {
            Path dir = Files.createTempDirectory(Paths.get("build/test-data"), "recovery-");
            try (FileRepository repo=new FileRepository(dir)) {
                Engine e=new Engine(repo); e.execute("CREATE TABLE x(id INT PRIMARY KEY);");
                e.execute("INSERT INTO x VALUES(1);"); e.execute("INSERT INTO x VALUES(2);");
            }
            Files.write(dir.resolve("database.bin"), new byte[]{1,2,3});
            try (FileRepository repo=new FileRepository(dir)) {
                Engine e=new Engine(repo);
                check(repo.recoveryMessage()!=null && e.execute("SELECT * FROM x;").rows.size()==1, "Возврат к предыдущему снимку");
                e.execute("INSERT INTO x VALUES(3);");
            }
            try (FileRepository repo=new FileRepository(dir)) {
                Engine e=new Engine(repo); check(e.execute("SELECT * FROM x;").rows.size()==2, "Сохранение после восстановления");
            }
        });
        group("Незавершённый tmp и повреждение обоих снимков", () -> {
            Path dir=Files.createTempDirectory(Paths.get("build/test-data"), "tmp-");
            try(FileRepository repo=new FileRepository(dir)) { Engine e=new Engine(repo); e.execute("CREATE TABLE x(id INT PRIMARY KEY);"); e.execute("INSERT INTO x VALUES(1);"); }
            Files.write(dir.resolve("database.tmp"), "не завершено".getBytes(StandardCharsets.UTF_8));
            try(FileRepository repo=new FileRepository(dir)) { Engine e=new Engine(repo); check(e.execute("SELECT * FROM x;").rows.size()==1, "tmp проигнорирован"); }
            byte[] original=Files.readAllBytes(dir.resolve("database.bin")); original[original.length-1]^=1;
            Files.write(dir.resolve("database.bin"),original); Files.write(dir.resolve("database.bak"),new byte[]{0});
            try(FileRepository repo=new FileRepository(dir)) { rejects(() -> new Engine(repo), "Оба снимка повреждены — отказ без перезаписи"); }
            check(Arrays.equals(original,Files.readAllBytes(dir.resolve("database.bin"))), "Повреждённый файл не уничтожен");
        });
        group("Блокировка второго экземпляра", () -> {
            Path dir=Files.createTempDirectory(Paths.get("build/test-data"), "lock-");
            try(FileRepository first=new FileRepository(dir)) {
                first.load();
                rejects(() -> { try(FileRepository second=new FileRepository(dir)) { second.load(); } }, "Второе открытие запрещено");
            }
            try(FileRepository next=new FileRepository(dir)) { check(next.load().tables.isEmpty(), "Блокировка снята"); }
        });
        group("Принудительное завершение отдельного процесса", () -> {
            Path dir=Files.createTempDirectory(Paths.get("build/test-data"), "crash-");
            String java=Paths.get(System.getProperty("java.home"),"bin","java.exe").toString();
            Process child=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),
                    "tennisdb.CrashProbe",dir.toAbsolutePath().toString()).inheritIO().start();
            check(child.waitFor()==37,"Процесс завершился через halt");
            try(FileRepository repo=new FileRepository(dir)) {
                Engine e=new Engine(repo);
                QueryResult q=e.execute("SELECT * FROM crash_test WHERE v='сохранено';");
                check(q.rows.size()==1 && q.plan.contains("idx_crash_v"),"Запись, индекс и снятие блокировки после halt");
            }
        });
        System.out.println("OK: " + groups + " групп, " + assertions + " проверок.");
    }
}
