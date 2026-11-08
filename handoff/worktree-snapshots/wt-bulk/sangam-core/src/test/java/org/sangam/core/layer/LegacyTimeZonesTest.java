// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

class LegacyTimeZonesTest {

    @Test
    void everyTargetIsAKnownZoneWithTheSameOffsetNow() {
        Instant now = Instant.now();
        for (Map.Entry<String, String> e : LegacyTimeZones.CANONICAL.entrySet()) {
            ZoneId legacy = ZoneId.of(e.getKey());
            ZoneId target = ZoneId.of(e.getValue());
            assertEquals(legacy.getRules().getOffset(now), target.getRules().getOffset(now), e.getKey());
        }
    }

    @Test
    void normalizesTheJvmDefaultAndLeavesOthersAlone() {
        TimeZone before = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Calcutta"));
            assertEquals("Asia/Kolkata", LegacyTimeZones.normalizeJvmDefault());
            assertEquals("Asia/Kolkata", TimeZone.getDefault().getID());
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"));
            assertEquals("Europe/Paris", LegacyTimeZones.normalizeJvmDefault());
        } finally {
            TimeZone.setDefault(before);
        }
    }
}
