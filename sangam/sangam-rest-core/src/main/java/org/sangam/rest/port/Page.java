// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.util.List;
import java.util.function.Function;

/**
 * One page of a list plus its total. {@code capped == true} means "more than {@code total}" (A-16): the REST layer
 * sends {@code X-Total-Count: total} and {@code X-Total-Count-Capped: true}.
 */
public record Page<T>(List<T> items, long total, boolean capped) {

    public Page {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static <T> Page<T> of(List<T> items, long total, boolean capped) {
        return new Page<>(items, total, capped);
    }

    /** Pages an in-memory list (adapters without a query engine), applying the count cap. */
    public static <T> Page<T> slice(List<T> all, PageRequest page, long countCap) {
        int from = Math.min(all.size(), page.firstResult());
        int to = Math.min(all.size(), from + page.size());
        boolean capped = all.size() > countCap;
        return new Page<>(all.subList(from, to), capped ? countCap : all.size(), capped);
    }

    public <R> Page<R> map(Function<T, R> f) {
        return new Page<>(items.stream().map(f).toList(), total, capped);
    }

    /** {@code ?page=&size=}: page is 0-based; size 1..{@link #MAX_SIZE}, default {@link #DEFAULT_SIZE}. */
    public record PageRequest(int page, int size) {
        public static final int DEFAULT_SIZE = 50;
        public static final int MAX_SIZE = 500;

        public PageRequest {
            if (page < 0) {
                throw ApiProblem.badRequest("INVALID_PAGE", "page must be >= 0");
            }
            if (size < 1 || size > MAX_SIZE) {
                throw ApiProblem.badRequest("INVALID_PAGE_SIZE", "size must be 1.." + MAX_SIZE);
            }
        }

        public static PageRequest of(Integer page, Integer size) {
            return new PageRequest(page == null ? 0 : page, size == null ? DEFAULT_SIZE : size);
        }

        public int firstResult() {
            return Math.multiplyExact(page, size);
        }
    }
}
