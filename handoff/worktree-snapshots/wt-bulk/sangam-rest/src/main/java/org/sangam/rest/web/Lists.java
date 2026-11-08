// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.net.URI;
import java.util.List;

import org.sangam.rest.port.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Response helpers: capped list headers (A-16), 201 with Location, ETag revisions. */
public final class Lists {

    public static final String TOTAL = "X-Total-Count";
    public static final String CAPPED = "X-Total-Count-Capped";
    public static final String RESOURCE_REF = "X-Sangam-Resource-Ref";

    private Lists() {
    }

    public static Page.PageRequest page(Integer page, Integer size) {
        return Page.PageRequest.of(page, size);
    }

    public static <T> ResponseEntity<List<T>> ok(Page<T> page) {
        HttpHeaders h = new HttpHeaders();
        h.set(TOTAL, Long.toString(page.total()));
        if (page.capped()) {
            h.set(CAPPED, "true");
        }
        return new ResponseEntity<>(page.items(), h, HttpStatus.OK);
    }

    public static <T> ResponseEntity<List<T>> ok(List<T> all) {
        HttpHeaders h = new HttpHeaders();
        h.set(TOTAL, Integer.toString(all.size()));
        return new ResponseEntity<>(all, h, HttpStatus.OK);
    }

    /** 201 Created with {@code Location} and the resource reference stored by the idempotency layer (R41). */
    public static <T> ResponseEntity<T> created(String location, String resourceRef, T body) {
        return ResponseEntity.created(URI.create(location)).header(RESOURCE_REF, resourceRef).body(body);
    }

    public static <T> ResponseEntity<T> okRef(String resourceRef, T body) {
        return ResponseEntity.ok().header(RESOURCE_REF, resourceRef).body(body);
    }

    public static <T> ResponseEntity<T> accepted(String resourceRef, T body) {
        return ResponseEntity.accepted().header(RESOURCE_REF, resourceRef).body(body);
    }

    public static <T> ResponseEntity<T> withRevision(long revision, T body) {
        return ResponseEntity.ok().eTag("\"" + revision + "\"").body(body);
    }

    /** Parses {@code If-Match: "<revision>"} (weak or strong); required where a revision precondition applies. */
    public static long ifMatchRevision(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new org.sangam.rest.port.ApiProblem(428, "PRECONDITION_REQUIRED",
                    "If-Match with the current revision is required");
        }
        String v = ifMatch.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        v = v.replace("\"", "");
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw org.sangam.rest.port.ApiProblem.badRequest("INVALID_IF_MATCH", "If-Match must carry a revision number");
        }
    }
}
