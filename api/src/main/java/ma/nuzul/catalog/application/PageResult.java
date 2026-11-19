package ma.nuzul.catalog.application;

import java.util.List;

/** One page of results: {@code page} is zero-based, {@code total} counts all matching rows. */
public record PageResult<T>(List<T> items, int page, int size, long total) {

    public PageResult {
        items = List.copyOf(items);
    }
}
