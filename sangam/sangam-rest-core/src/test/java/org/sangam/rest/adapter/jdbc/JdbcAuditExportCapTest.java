// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.sangam.integration.document.pdf.PdfRendering;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.support.BlobStore;
import org.sangam.rest.support.DocumentStore;
import org.sangam.rest.support.RestProperties;

/**
 * Code scanning java/comparison-with-wider-type (#682, #683): the export's page loop compared an {@code int} page
 * counter with the {@code long} count cap and added whole pages, so it overshot the cap by up to a page and, for a cap
 * above {@code Integer.MAX_VALUE}, had no end. The cap is now one int and the last page is cut to it.
 */
class JdbcAuditExportCapTest {

    private static final int PAGE = 500;

    private final AtomicInteger reads = new AtomicInteger();
    private final List<Page.PageRequest> requested = new ArrayList<>();

    private static AuditPort.AuditEntry entry(long seq) {
        return new AuditPort.AuditEntry("e" + seq, "B", "chain-1", seq, "TASK_COMPLETED", Instant.parse("2026-11-12T10:00:00Z"),
                "user-1", null, null, "pi-1", null, null, Map.of(), false, null, false, "p" + seq, "h" + seq, null);
    }

    /** An audit port whose timeline and search return a full page for every page asked, without end. */
    private AuditPort endless() {
        return (AuditPort) Proxy.newProxyInstance(AuditPort.class.getClassLoader(), new Class<?>[]{AuditPort.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("timeline") && !method.getName().equals("search")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    Page.PageRequest page = (Page.PageRequest) args[args.length - 1];
                    requested.add(page);
                    reads.incrementAndGet();
                    List<AuditPort.AuditEntry> items = new ArrayList<>();
                    for (int i = 0; i < page.size(); i++) {
                        items.add(entry((long) page.firstResult() + i + 1));
                    }
                    return Page.of(items, Long.MAX_VALUE, true);
                });
    }

    private JdbcAuditExportAdapter adapter(long countCap) {
        RestProperties props = new RestProperties();
        props.setCountCap(countCap);
        return new JdbcAuditExportAdapter(endless(), null, null, null, props, new DocumentStore(null), new BlobStore(null),
                PdfRendering.unavailable());
    }

    @Test
    void aTimelineExportHoldsExactlyTheCapAndStops() {
        try (JdbcAuditExportAdapter a = adapter(250)) {
            List<AuditPort.AuditEntry> out = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> a.collect(null, Map.of("businessKey", "BK-1")));
            assertEquals(250, out.size(), "never more rows than the count cap");
            assertEquals(1L, out.get(0).seq());
            assertEquals(250L, out.get(249).seq(), "the first rows of the page, in order");
            assertEquals(1, reads.get(), "no page is read once the cap is reached");
        }
    }

    @Test
    void aFilteredExportHoldsExactlyTheCapAndStops() {
        try (JdbcAuditExportAdapter a = adapter(1_250)) {
            List<AuditPort.AuditEntry> out = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> a.collect(null, Map.of("type", "TASK_COMPLETED")));
            assertEquals(1_250, out.size());
            assertEquals(1_250L, out.get(1_249).seq());
            assertEquals(List.of(new Page.PageRequest(0, PAGE), new Page.PageRequest(1, PAGE), new Page.PageRequest(2, PAGE)),
                    requested, "whole pages are asked for; only the last one is cut");
        }
    }

    @Test
    void aCapAboveTheIntRangeIsOneIntBoundAndTheLoopEndsOnAShortPage() {
        AtomicInteger calls = new AtomicInteger();
        List<Integer> out = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> JdbcAuditExportAdapter.<Integer>paged(
                Integer.MAX_VALUE, page -> {
                    int n = calls.getAndIncrement() < 2 ? page.size() : 7;
                    return Page.<Integer>of(java.util.Collections.nCopies(n, 1), n, false);
                }));
        assertEquals(2 * PAGE + 7, out.size());
        try (JdbcAuditExportAdapter a = adapter(0)) {
            assertEquals(0, a.collect(null, Map.of("businessKey", "BK-1")).size(), "a cap of 0 reads nothing");
            assertEquals(0, reads.get());
        }
    }
}
