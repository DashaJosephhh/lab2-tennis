package tennisdb;

import java.util.ArrayList;
import java.util.List;

/** Результат команды и измеримые сведения о выбранном способе поиска. */
public final class QueryResult {
    public final List<String> columns = new ArrayList<>();
    public final List<List<Object>> rows = new ArrayList<>();
    public String message;
    public String plan;
    public int examined;
    public boolean explain;
    static QueryResult message(String text) {
        QueryResult result = new QueryResult(); result.message = text; return result;
    }
}
