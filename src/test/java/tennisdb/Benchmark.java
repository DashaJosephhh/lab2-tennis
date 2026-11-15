package tennisdb;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Сравнивает выполнение одного SELECT: принудительный scan и сохранённый AVL-индекс. */
public final class Benchmark {
    private static volatile long sink;
    private static final int WARMUP = 80, ROUNDS = 7, REPEATS = 100;
    private static final String QUERY = "SELECT id,total_price FROM bookings WHERE booking_date='2026-05-08';";
    private Benchmark() { }

    public static void main(String[] args) throws Exception {
        Path reports = args.length == 0 ? Paths.get("reports") : Paths.get(args[0]);
        Files.createDirectories(reports);
        Files.createDirectories(Paths.get("build/benchmark-data"));
        try (PrintWriter summary = writer(reports.resolve("benchmark.csv"));
             PrintWriter raw = writer(reports.resolve("benchmark-runs.csv"));
             PrintWriter metadata = writer(reports.resolve("benchmark-environment.txt"))) {
            summary.println("rows,matches,scan_median_us,index_median_us,speedup,scan_examined,index_examined,index_height");
            raw.println("rows,round,scan_us,index_us");
            metadata.println("Дата запуска (локальная): " + java.time.LocalDateTime.now());
            metadata.println("Java: " + System.getProperty("java.version") + " / " + System.getProperty("java.vm.name"));
            metadata.println("ОС: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
            metadata.println("CPU: " + System.getenv("PROCESSOR_IDENTIFIER"));
            metadata.println("Доступно процессоров JVM: " + Runtime.getRuntime().availableProcessors());
            metadata.println("Макс. память JVM: " + Runtime.getRuntime().maxMemory());
            metadata.println("warmup=" + WARMUP + ", rounds=" + ROUNDS + ", repeats=" + REPEATS);
            metadata.println("SQL: " + QUERY);
            metadata.println("Время: System.nanoTime; медиана средних времен серий, микросекунды/запрос.");
            metadata.println("Измеряется SELECT после загрузки снимка с диска; без парсинга SQL и вывода.");
            metadata.println("Данные: 1000 различных дат, равномерное распределение; выборка 0.1%.");
            for (int n : new int[]{1000,10000,100000}) measure(n, summary, raw);
        }
        System.out.println("Результаты: " + reports.toAbsolutePath());
    }
    private static PrintWriter writer(Path file) throws IOException {
        return new PrintWriter(Files.newBufferedWriter(file,StandardCharsets.UTF_8));
    }
    private static void measure(int n, PrintWriter summary, PrintWriter raw) throws Exception {
        Path dir = Files.createTempDirectory(Paths.get("build/benchmark-data"), "n" + n + "-");
        try (FileRepository repo = new FileRepository(dir)) {
            Engine engine = new Engine(repo);
            engine.execute("CREATE TABLE members(id INT PRIMARY KEY,full_name STRING);");
            engine.execute("CREATE TABLE courts(id INT PRIMARY KEY,name STRING);");
            engine.execute("CREATE TABLE bookings(id INT PRIMARY KEY,member_id INT REFERENCES members(id),"
                    + "court_id INT REFERENCES courts(id),booking_date DATE,total_price DOUBLE,paid BOOLEAN);");
            engine.execute("INSERT INTO members VALUES(1,'Benchmark');");
            engine.execute("INSERT INTO courts VALUES(1,'Benchmark');");
            StringBuilder insert = new StringBuilder("INSERT INTO bookings VALUES ");
            LocalDate base = LocalDate.of(2026,1,1);
            for (int i=0;i<n;i++) {
                if (i>0) insert.append(',');
                insert.append('(').append(i+1).append(",1,1,'").append(base.plusDays(i%1000))
                      .append("',").append(1000+i%100).append(".0,TRUE)");
            }
            insert.append(';'); engine.execute(insert.toString());
            engine.execute("CREATE INDEX idx_benchmark_date ON bookings(booking_date);");
        }
        // Повторное открытие обязательно: измеряется именно восстановленный с диска индекс.
        try (FileRepository repo = new FileRepository(dir)) {
            Engine engine = new Engine(repo);
            Sql.Command command = Sql.parse(QUERY);
            QueryResult scan=engine.execute(command,true), indexed=engine.execute(command,false);
            if (!new HashSet<>(scan.rows).equals(new HashSet<>(indexed.rows)) || !indexed.plan.contains("INDEX"))
                throw new AssertionError("Результат индекса не совпадает со scan");
            if(indexed.rows.size()!=n/1000) throw new AssertionError("Неверная селективность");
            for(int i=0;i<WARMUP;i++) { consume(engine.execute(command,true)); consume(engine.execute(command,false)); }
            double[] scans=new double[ROUNDS], indexes=new double[ROUNDS];
            for(int round=0;round<ROUNDS;round++) {
                // Чередуем порядок серий, чтобы уменьшить зависимость от порядка измерения.
                if(round%2==0) {
                    scans[round]=batch(engine,command,true); indexes[round]=batch(engine,command,false);
                } else {
                    indexes[round]=batch(engine,command,false); scans[round]=batch(engine,command,true);
                }
                raw.printf(Locale.ROOT,"%d,%d,%.6f,%.6f%n",n,round+1,scans[round],indexes[round]);
            }
            Arrays.sort(scans); Arrays.sort(indexes);
            double scanTime=scans[ROUNDS/2],indexTime=indexes[ROUNDS/2];
            int height=engine.state().table("bookings").indexes.get("idx_benchmark_date").height();
            summary.printf(Locale.ROOT,"%d,%d,%.6f,%.6f,%.2f,%d,%d,%d%n",n,indexed.rows.size(),scanTime,indexTime,
                    scanTime/indexTime,scan.examined,indexed.examined,height);
            summary.flush(); raw.flush();
            System.out.printf(Locale.ROOT,"%,d записей: scan %.3f us; AVL %.3f us; %.1fx%n",n,scanTime,indexTime,scanTime/indexTime);
        }
    }
    private static double batch(Engine engine, Sql.Command command, boolean scan) throws IOException {
        long start=System.nanoTime();
        for(int i=0;i<REPEATS;i++) consume(engine.execute(command,scan));
        return (System.nanoTime()-start)/1000.0/REPEATS;
    }
    private static void consume(QueryResult result) {
        long sum=result.rows.size();
        for(List<Object> row:result.rows) sum+=(Integer)row.get(0);
        sink=sum;
    }
}
