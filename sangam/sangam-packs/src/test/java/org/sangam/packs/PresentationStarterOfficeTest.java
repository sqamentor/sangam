// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * P-6 Step 2, owner decision (D) "Starter's DDO office" ([JOINT-DECISION]): a presentation case whose starter acts on
 * no seat (an employee's own request, IX.5 rule 7 gives it no office) names the office of the starter's DDO as its
 * {@code officeId}: the data set's office whose {@code ddoCode} is the DDO of the case. Without it the first seat step
 * is refused (422 SEAT_UNRESOLVED). Data only; rule 7 is unchanged.
 */
class PresentationStarterOfficeTest {

    @Test
    void aCaseStartedOnNoSeatNamesTheStartersDdoOffice() throws Exception {
        JsonNode set = new ObjectMapper().readTree(PresentationDataSetH2Test.DATA.toFile());
        JsonNode office = set.path("office");
        List<String> wrong = new ArrayList<>();
        for (JsonNode c : set.path("cases")) {
            JsonNode seat = set.path("people").path(c.path("startedBy").asText()).path("seat");
            if (!seat.isNull() && !seat.isMissingNode()) {
                continue;
            }
            JsonNode v = c.path("variables");
            String ddo = v.path("ddoCode").asText(office.path("ddoCode").asText());
            if (!ddo.equals(office.path("ddoCode").asText()) || !office.path("id").asText().equals(v.path("officeId").asText())) {
                wrong.add(c.path("id").asText() + ": officeId " + v.path("officeId").asText(null) + ", DDO " + ddo);
            }
        }
        assertTrue(wrong.isEmpty(), "cases started on no seat without the DDO office " + office.path("id").asText()
                + ":\n" + String.join("\n", wrong));
    }
}
