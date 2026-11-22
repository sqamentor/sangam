// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;

/**
 * P-6 Step 2, pack data: a seat is a designation in an office (SANGAM.md IX.5 rule 1) and seat ids are "stable; never
 * reused" (AJ-02 step 2). Every shipped pack can be deployed into one tenant, and a tenant keeps one seat per id, so a
 * seat id that two packs' seeds declare must be the same seat: the same designation in the same office. A reused id
 * for another seat silently moves the first pack's seat to the last pack's office, and the first pack's steps then
 * resolve no seat (422 SEAT_UNRESOLVED).
 */
class SeedSeatIdsUniqueTest {

    @Test
    void aSeatIdThatTwoPacksDeclareIsTheSameSeat() {
        Map<String, String> first = new LinkedHashMap<>();
        Map<String, String> firstPack = new LinkedHashMap<>();
        List<String> reused = new ArrayList<>();
        for (SangamPack p : PackRegistry.discover().deploymentOrder()) {
            for (PackDescriptor.SeedSeat s : p.descriptor().seats()) {
                String seat = s.role() + "@" + s.office();
                String was = first.putIfAbsent(s.id(), seat);
                if (was == null) {
                    firstPack.put(s.id(), p.descriptor().id());
                } else if (!was.equals(seat)) {
                    reused.add(s.id() + ": " + firstPack.get(s.id()) + " " + was + ", " + p.descriptor().id() + " " + seat);
                }
            }
        }
        assertTrue(reused.isEmpty(), reused.size() + " seat id(s) reused for another seat:\n" + String.join("\n", reused));
    }
}
