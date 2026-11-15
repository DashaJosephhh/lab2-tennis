package tennisdb;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Файловый Repository: сериализация, SHA-256, временный файл, fsync и замена снимка.
 * Предыдущая зафиксированная версия хранится в database.bak; один процесс владеет базой.
 */
public final class FileRepository implements Repository {
    private static final int MAGIC = 0x54444232;
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 256 * 1024 * 1024;
    private final Path main, backup, temporary, backupTemporary;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private boolean primaryValid;
    private boolean fresh;
    private String recoveryMessage;

    public FileRepository(Path directory) throws IOException {
        Files.createDirectories(directory);
        main = directory.resolve("database.bin"); backup = directory.resolve("database.bak");
        temporary = directory.resolve("database.tmp"); backupTemporary = directory.resolve("backup.tmp");
        lockChannel = FileChannel.open(directory.resolve("database.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (OverlappingFileLockException ex) { acquired = null; }
        catch (IOException ex) { lockChannel.close(); throw ex; }
        if (acquired == null) {
            lockChannel.close();
            throw new IOException("База уже открыта другим экземпляром программы: " + directory);
        }
        lock = acquired;
    }
    public boolean isFresh() { return fresh; }
    public String recoveryMessage() { return recoveryMessage; }

    @Override public Database load() throws IOException {
        fresh = !Files.exists(main) && !Files.exists(backup);
        if (fresh) {
            if (Files.exists(temporary)) recoveryMessage = "Незавершённый первый снимок проигнорирован; база пуста.";
            return new Database();
        }
        IOException primaryError;
        try {
            Database d = read(main); primaryValid = true; return d;
        } catch (IOException ex) { primaryError = ex; }
        try {
            Database d = read(backup);
            primaryValid = false;
            recoveryMessage = "Основной снимок недоступен. Загружена резервная копия предыдущего состояния. Причина: "
                    + primaryError.getMessage();
            return d;
        } catch (IOException ex) {
            IOException failure = new IOException("Не удалось прочитать основной снимок и резервную копию. Файлы сохранены для восстановления.", primaryError);
            failure.addSuppressed(ex); throw failure;
        }
    }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static Database read(Path path) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException("Неверный формат: " + path);
            int length = input.readInt();
            if (length < 0 || length > MAX_BYTES || Files.size(path) != 44L + length)
                throw new IOException("Неверная длина снимка: " + path);
            byte[] expected = new byte[32], bytes = new byte[length];
            input.readFully(expected); input.readFully(bytes);
            if (!Arrays.equals(expected, digest(bytes))) throw new IOException("Не совпала SHA-256: " + path);
            try (ObjectInputStream objects = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                Object object = objects.readObject();
                if (!(object instanceof Database)) throw new IOException("В файле нет Database");
                Database d = (Database) object;
                d.validate();
                return d;
            } catch (ClassNotFoundException | RuntimeException ex) {
                throw new IOException("Не удалось восстановить снимок: " + path, ex);
            }
        }
    }
    @Override public void save(Database database) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream objects = new ObjectOutputStream(bytes)) { objects.writeObject(database); }
        byte[] payload = bytes.toByteArray();
        if (payload.length > MAX_BYTES) throw new IOException("Превышен лимит снимка 256 MiB");
        try (FileOutputStream file = new FileOutputStream(temporary.toFile());
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(file))) {
            output.writeInt(MAGIC); output.writeInt(VERSION); output.writeInt(payload.length);
            output.write(digest(payload)); output.write(payload); output.flush(); file.getFD().sync();
        }
        if (primaryValid && Files.exists(main)) {
            Files.copy(main, backupTemporary, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel channel = FileChannel.open(backupTemporary, StandardOpenOption.WRITE)) { channel.force(true); }
            replace(backupTemporary, backup);
        }
        replace(temporary, main);
        primaryValid = true; fresh = false;
    }
    private static void replace(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }
    @Override public void close() throws IOException {
        try { lock.release(); } finally { lockChannel.close(); }
    }
}
