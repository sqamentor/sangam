// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.integration;

import org.junit.jupiter.api.Test;
import org.sangam.core.engine.SangamClock;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M-097 / D11: the time left before a connector call's deadline is read on an injectable clock. */
class ConnectorRequestDeadlineTest {

    private static ConnectorRequest request(Instant deadline) {
        return new ConnectorRequest("t1", UUID.nameUUIDFromBytes(new byte[] {1}), "a1", 1, "root-1", null, "send",
                Map.of(), deadline);
    }

    @Test
    void remainingTimeIsMeasuredOnTheGivenClock() {
        SangamClock at4s = () -> Instant.ofEpochMilli(4_000);
        assertEquals(6_000, request(Instant.ofEpochMilli(10_000)).remainingMillis(99, at4s));
        assertEquals(0, request(Instant.ofEpochMilli(3_000)).remainingMillis(99, at4s), "a passed deadline is 0, never negative");
        assertEquals(0, request(Instant.ofEpochMilli(4_000)).remainingMillis(99, at4s));
    }

    @Test
    void noDeadlineGivesTheFallbackOnAnyClock() {
        assertEquals(99, request(null).remainingMillis(99, () -> Instant.ofEpochMilli(4_000)));
        assertEquals(99, request(null).remainingMillis(99));
    }

    @Test
    void theOneArgumentFormKeepsTheWallClock() {
        long left = request(Instant.now().plusSeconds(60)).remainingMillis(0);
        assertTrue(left > 50_000 && left <= 60_000, "about a minute left on the JVM wall clock: " + left);
        assertEquals(0, request(Instant.now().minusSeconds(1)).remainingMillis(5));
    }
}
