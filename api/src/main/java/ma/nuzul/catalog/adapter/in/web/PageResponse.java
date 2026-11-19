package ma.nuzul.catalog.adapter.in.web;

import java.util.List;
import java.util.function.Function;
import ma.nuzul.catalog.application.PageResult;

record PageResponse<T>(List<T> items, int page, int size, long total) {

    static <S, T> PageResponse<T> from(PageResult<S> result, Function<S, T> mapper) {
        return new PageResponse<>(
                result.items().stream().map(mapper).toList(), result.page(), result.size(), result.total());
    }
}
