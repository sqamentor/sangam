// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules;

import java.util.List;

import org.springframework.http.ResponseEntity;

/** Slices a module list that has no paged query into the {@code ?page=&size=} and {@code X-Total-Count} list contract. */
public final class ModulePaging {

    private ModulePaging() {
    }

    public static <T> ResponseEntity<List<T>> slice(List<T> all, Integer page, Integer size) {
        int s = size == null ? 25 : Math.min(Math.max(size, 1), 200);
        int p = page == null ? 0 : Math.max(page, 0);
        long from = Math.min((long) p * s, all.size());
        long to = Math.min(from + s, all.size());
        return ResponseEntity.ok().header("X-Total-Count", Integer.toString(all.size())).body(List.copyOf(all.subList((int) from, (int) to)));
    }
}
