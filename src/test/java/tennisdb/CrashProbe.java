package tennisdb;

import java.nio.file.Paths;

/** Вспомогательный процесс: завершение без close() после зафиксированной записи. */
public final class CrashProbe {
    private CrashProbe() { }
    public static void main(String[] args) throws Exception {
        FileRepository repo = new FileRepository(Paths.get(args[0]));
        Engine engine = new Engine(repo);
        engine.execute("CREATE TABLE crash_test(id INT PRIMARY KEY,v STRING);");
        engine.execute("CREATE INDEX idx_crash_v ON crash_test(v);");
        engine.execute("INSERT INTO crash_test VALUES(1,'сохранено');");
        Runtime.getRuntime().halt(37);
    }
}
