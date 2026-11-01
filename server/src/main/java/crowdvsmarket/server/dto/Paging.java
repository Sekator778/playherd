package crowdvsmarket.server.dto;

import java.util.List;

/** contract.ts §"Pagination" (proposal — no source fixes a page size/cursor shape). */
public final class Paging {
    private Paging() {
    }

    public record PageParams(Integer limit, Long before) {
        public static final int DEFAULT_LIMIT = 20;

        public int limitOrDefault() {
            return limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 200);
        }
    }

    public record Page<T>(List<T> items, boolean hasMore) {
    }
}
