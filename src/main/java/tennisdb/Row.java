package tennisdb;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Неизменяемая запись: значения идут в порядке столбцов схемы. */
public final class Row implements Serializable {
    private static final long serialVersionUID = 1L;
    private final List<Object> values;
    public Row(List<Object> values) { this.values = Collections.unmodifiableList(new ArrayList<>(values)); }
    public Object get(int position) { return values.get(position); }
    public List<Object> values() { return values; }
}
