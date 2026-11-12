// SPDX-License-Identifier: Apache-2.0
package org.sangam.rollup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.cell.rollup.RollupEnvelope;

/** M-063: one definition of what an event counts, shared by the per-event and the batch path. */
class RollupEventsTest {

    @Test
    void streamEventCountsOnePerMetricAndDerivesTheOpenGauge() {
        RollupEvents.Effect start = RollupEvents.effect(RollupTestDb.start("e1", "IN/RJ/RJ-JPR/H1"), true);
        assertEquals(20261112, start.day());
        assertEquals(List.of("IN", "IN/RJ", "IN/RJ/RJ-JPR", "IN/RJ/RJ-JPR/H1"), start.prefixes());
        assertEquals(List.of(new RollupEvents.Delta("process.start", 1), new RollupEvents.Delta("process.start#p1", 1),
                new RollupEvents.Delta("process.open", 1)), start.deltas());
        RollupEvents.Effect end = RollupEvents.effect(RollupTestDb.end("e2", "IN/RJ"), false);
        assertEquals(List.of(new RollupEvents.Delta("process.end", 1), new RollupEvents.Delta("process.open", -1)),
                end.deltas());
    }

    @Test
    void explicitMetricCarriesItsOwnMetricDayAndDelta() {
        RollupEnvelope e = RollupTestDb.metric("m1", "IN/RJ/RJ-JPR", "task.complete", 20261111L, 42L);
        assertTrue(RollupEvents.explicit(e));
        assertNull(RollupEvents.validate(e, true, ""));
        RollupEvents.Effect f = RollupEvents.effect(e, true);
        assertEquals(20261111, f.day());
        assertEquals(List.of(new RollupEvents.Delta("task.complete", 42)), f.deltas(), "no per-definition, no gauge");
        assertEquals(List.of("IN", "IN/RJ", "IN/RJ/RJ-JPR"), f.prefixes());
        assertEquals(-7, RollupEvents.effect(RollupTestDb.metric("m2", "IN", "process.open", 20261111L, -7L), true)
                .deltas().getFirst().value(), "a negative delta is allowed (gauges)");
    }

    @Test
    void explicitMetricNeedsTheTierRole() {
        RollupEnvelope e = RollupTestDb.metric("m1", "IN/RJ", "task.complete", 20261111L, 3L);
        assertEquals("an explicit metric event needs the TIER role", RollupEvents.validate(e, false, ""));
    }

    @Test
    void explicitMetricFieldsAreChecked() {
        assertNotNull(RollupEvents.validate(withData(Map.of("metric", "", "day", 20261111L, "delta", 1L)), true, ""));
        assertNotNull(RollupEvents.validate(withData(Map.of("metric", "x".repeat(97), "day", 20261111L, "delta", 1L)),
                true, ""));
        assertNotNull(RollupEvents.validate(withData(Map.of("metric", "m", "day", 20261111L, "delta", 1.5d)), true, ""),
                "a fractional delta");
        assertNotNull(RollupEvents.validate(withData(Map.of("metric", "m", "day", 20261131L, "delta", 1L)), true, ""),
                "31 November");
        assertNotNull(RollupEvents.validate(withData(Map.of("metric", "m", "delta", 1L)), true, ""), "no day");
        assertNull(RollupEvents.validate(withData(Map.of("metric", "m", "day", 20240229L, "delta", 0L)), true, ""));
    }

    @Test
    void metricTypeWithoutDataMetricKeepsTheStreamMeaning() {
        RollupEnvelope e = RollupTestDb.event("x1", RollupEvents.METRIC_TYPE, "IN/RJ", "2026-11-12T00:00:00Z", Map.of());
        assertFalse(RollupEvents.explicit(e));
        assertNull(RollupEvents.validate(e, false, ""), "a cell may still send it");
        assertEquals(List.of(new RollupEvents.Delta(RollupEvents.METRIC_TYPE, 1)), RollupEvents.effect(e, true).deltas());
    }

    @Test
    void scopeRejectsPathsOutsideTheStateAndAcceptsTheScopeNodeItself() {
        assertNull(RollupEvents.validate(RollupTestDb.start("a", "IN/RJ/RJ-JPR"), false, "IN/RJ"));
        assertNull(RollupEvents.validate(RollupTestDb.start("b", "IN/RJ"), false, "IN/RJ"));
        assertNotNull(RollupEvents.validate(RollupTestDb.start("c", "IN/RJX/H"), false, "IN/RJ"), "prefix of a name");
        assertNotNull(RollupEvents.validate(RollupTestDb.start("d", "IN/MH/H"), false, "IN/RJ"));
        assertNotNull(RollupEvents.validate(RollupTestDb.start("e", "IN"), false, "IN/RJ"), "an ancestor of the scope");
        assertNull(RollupEvents.validate(RollupTestDb.start("f", "IN/MH/H"), false, ""), "no scope = everything");
    }

    @Test
    void streamValidationKeepsTheEightPointTwoRules() {
        assertNotNull(RollupEvents.validate(RollupTestDb.start("", "IN"), false, ""));
        assertNotNull(RollupEvents.validate(RollupTestDb.start("x".repeat(65), "IN"), false, ""));
        assertNotNull(RollupEvents.validate(RollupTestDb.start("ok", " / "), false, ""), "blank segments only");
    }

    @Test
    void cutNodeIsTheAncestorAtTheForwardDepthOrTheDeepestPrefix() {
        List<String> p = RollupProjector.prefixes("IN/RJ/RJ-JPR/H1");
        assertEquals("IN", RollupEvents.cutNode(p, 0));
        assertEquals("IN/RJ/RJ-JPR", RollupEvents.cutNode(p, 2));
        assertEquals("IN/RJ/RJ-JPR/H1", RollupEvents.cutNode(p, 9));
        assertEquals("IN/RJ", RollupEvents.cutNode(RollupProjector.prefixes("IN/RJ"), 2));
    }

    @Test
    void forwardDepthDefaultsToOneLevelBelowTheScope() {
        assertEquals(-1, RollupProjector.forwardDepth(RollupTestDb.props(true, "IN/RJ", null)), "no upstream");
        assertEquals(2, RollupProjector.forwardDepth(RollupTestDb.props(true, "IN/RJ", RollupTestDb.upstream("rj", null))));
        assertEquals(1, RollupProjector.forwardDepth(RollupTestDb.props(true, "", RollupTestDb.upstream("rj", null))));
        assertEquals(3, RollupProjector.forwardDepth(RollupTestDb.props(true, "IN/RJ", RollupTestDb.upstream("rj", 3))));
    }

    @Test
    void propertiesNormaliseTheScopeAndRequireAnInstanceIdWhenForwarding() {
        assertEquals("IN/RJ", RollupTestDb.props(true, " /IN/ RJ /", null).scope());
        assertFalse(RollupTestDb.props(true, "", null).upstream().enabled());
        try {
            new RollupProperties.Upstream("http://x/api/v1/ingest", null, null, null, null, null, null, null);
            throw new AssertionError("an upstream without instance-id must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("instance-id"));
        }
        String shown = new RollupProperties.Upstream("http://x", "s3cret", null, null, "rj", null, null, null).toString();
        assertFalse(shown.contains("s3cret"), "the token is never printed");
    }

    private static RollupEnvelope withData(Map<String, Object> data) {
        return new RollupEnvelope("m", "s", RollupEvents.METRIC_TYPE, null, "rj", null, "IN/RJ", null, 0L, null, data);
    }
}
