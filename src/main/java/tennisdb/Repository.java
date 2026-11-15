package tennisdb;

import java.io.IOException;

/** Repository отделяет движок запросов от способа хранения снимка базы. */
public interface Repository extends AutoCloseable {
    Database load() throws IOException;
    void save(Database database) throws IOException;
    @Override void close() throws IOException;
}
