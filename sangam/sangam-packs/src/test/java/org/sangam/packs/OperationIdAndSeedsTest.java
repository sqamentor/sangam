// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import org.sangam.core.layer.Hashes;
import org.sangam.packs.core.Vars;

class OperationIdAndSeedsTest {

    @Test
    void operationIdIsAStableUuidPerTenant() {
        String a = Vars.operationId("state-a", "BILL/1", "payment", 1);
        assertEquals(a, Vars.operationId("state-a", "BILL/1", "payment", 1));
        assertEquals(5, UUID.fromString(a).version());
        assertEquals(a, UUID.fromString(a).toString());
        assertNotEquals(a, Vars.operationId("state-b", "BILL/1", "payment", 1));
        assertNotEquals(a, Vars.operationId("state-a", "BILL/1", "payment", 2));
        assertEquals(Hashes.uuidV5(Vars.OPERATION_NS, "BILL/1|payment|1").toString(),
                Vars.operationId(null, "BILL/1", "payment", 1), "no tenant keeps the earlier id");
    }

    @Test
    void identifierTypeSeedsAreReadable() throws Exception {
        for (String path : new String[] {"packs/core/registry/identifier-types.json", "packs/core/registry/identifier-types-in.json",
                "packs/sec-ushealth-claim/registry/identifier-types-us.json"}) {
            try (var in = getClass().getClassLoader().getResourceAsStream(path)) {
                JsonNode types = new ObjectMapper().readTree(in).path("identifierTypes");
                assertTrue(types.size() > 0, path);
                for (JsonNode t : types) {
                    assertTrue(t.hasNonNull("typeKey"));
                    if (t.hasNonNull("pattern")) {
                        Pattern.compile(t.get("pattern").asText());
                    }
                }
            }
        }
    }
}
