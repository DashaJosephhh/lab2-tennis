package tennisdb;

import java.io.IOException;

/** Минимальная предметная область: участники, корты, бронирования. */
public final class Demo {
    private Demo() { }
    public static final String SCHEMA =
        "CREATE TABLE members (id INT PRIMARY KEY, full_name STRING NOT NULL, joined DATE);\n" +
        "CREATE TABLE courts (id INT PRIMARY KEY, name STRING NOT NULL, hourly_rate DOUBLE, indoor BOOLEAN);\n" +
        "CREATE TABLE bookings (id INT PRIMARY KEY, member_id INT NOT NULL REFERENCES members(id), " +
        "court_id INT NOT NULL REFERENCES courts(id), booking_date DATE NOT NULL, start_minute INT, " +
        "duration_minutes INT, total_price DOUBLE, paid BOOLEAN);\n" +
        "INSERT INTO members VALUES (1, 'Дарья Иванова', '2026-09-01'), (2, 'Анна Петрова', '2026-09-10');\n" +
        "INSERT INTO courts VALUES (1, 'Корт 1', 1500.0, TRUE), (2, 'Корт 2', 1200.0, FALSE);\n" +
        "INSERT INTO bookings VALUES (1, 1, 1, '2026-11-20', 600, 60, 1500.0, TRUE), " +
        "(2, 2, 2, '2026-11-21', 720, 90, 1800.0, FALSE);\n" +
        "CREATE INDEX idx_bookings_date ON bookings (booking_date);\n" +
        "CREATE INDEX idx_members_name ON members (full_name);\n";
    public static void initialize(Engine engine) throws IOException {
        for (String sql : Sql.split(SCHEMA).complete) engine.execute(sql);
    }
}
