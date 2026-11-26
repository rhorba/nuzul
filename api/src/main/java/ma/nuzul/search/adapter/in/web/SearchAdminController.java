package ma.nuzul.search.adapter.in.web;

import ma.nuzul.search.application.ReindexResult;
import ma.nuzul.search.application.ReindexUseCase;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform search administration (story 3.2). A reindex is synchronous: the response comes once the new index is live
 * and caught up; 409 {@code REINDEX_IN_PROGRESS} while another one runs.
 */
@RestController
@RequestMapping("/api/v1/admin/search")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class SearchAdminController {

    private final ReindexUseCase reindex;

    SearchAdminController(ReindexUseCase reindex) {
        this.reindex = reindex;
    }

    @PostMapping("/reindex")
    ReindexResponse reindex() {
        ReindexResult result = reindex.reindex();
        return new ReindexResponse(result.index(), result.indexed(), result.caughtUp());
    }

    record ReindexResponse(String index, int indexed, int caughtUp) {}
}
