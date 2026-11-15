// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sangam.resources.catalogue.Resource;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.OpsPort;
import org.sangam.rest.port.PortalPort;
import org.sangam.rest.port.PowerTablePort;
import org.sangam.rest.port.ProcessPort;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lane D5 item 1 (SL-81): a request record whose primitive member is server-assigned, or an optional flag or paging
 * number with a documented default, binds through a {@code @JsonCreator} that applies that default once, so a client
 * may leave it out. Jackson 3's strict default ({@code FAIL_ON_NULL_FOR_PRIMITIVES}) stays for every other record: a
 * primitive the caller must choose is still refused when missing, and the contract lists it in {@code required}
 * (PrimitiveRequestFieldContractTest). Before the fix, each of the omitted-member cases below was refused (400
 * VALIDATION_FAILED on the wire), although the contract called the member optional.
 */
class RequestRecordDefaultsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static <T> T read(String json, Class<T> type) {
        return JSON.readValue(json, type);
    }

    @Test
    void theMapperKeepsJacksonThreesStrictDefault() {
        assertTrue(JSON.isEnabled(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES));
        // an operator choice without a default is still refused when it is missing
        assertThrows(MismatchedInputException.class, () -> read(
                "{\"processDefinitionId\":\"p:1\",\"processInstanceIds\":[\"i\"],\"initialVariables\":true,"
                        + "\"reason\":\"r\"}", OpsPort.RestartRequest.class));
    }

    @Test
    void serverAssignedAdminMembersMayBeOmitted() {
        AdminPort.OrgUnit o = read("{\"code\":\"DIST-01\",\"name\":\"District office\"}", AdminPort.OrgUnit.class);
        assertNull(o.active(), "an omitted active stays unset: create assigns true, an update keeps the current value");
        assertEquals(0L, o.revision(), "none read");
        AdminPort.Seat s = read("{\"orgUnitId\":\"ou-1\",\"code\":\"S1\",\"designation\":\"Clerk\"}", AdminPort.Seat.class);
        assertNull(s.active());
        assertEquals(0L, s.revision());
        assertFalse(read("{\"seatId\":\"s\",\"personId\":\"p\",\"kind\":\"SUBSTANTIVE\",\"validFrom\":\"2026-11-15\"}",
                AdminPort.Assignment.class).ended());
        assertFalse(read("{\"kind\":\"TASK\",\"validFrom\":\"2026-11-15\"}", AdminPort.Delegation.class).revoked());
        assertEquals(0L, read("{\"effect\":\"GRANT\",\"principalType\":\"USER\",\"principalId\":\"u\","
                + "\"resourceType\":\"case\",\"resourceId\":\"*\",\"actions\":[\"read\"]}", AdminPort.Policy.class)
                .policyVersion());
        assertEquals(0L, read("{\"id\":\"t1\",\"name\":\"Tenant\"}", AdminPort.Tenant.class).revision());
        PowerTablePort.PowerTable t = read("{\"key\":\"k\",\"name\":\"n\",\"dmnXml\":\"<x/>\"}",
                PowerTablePort.PowerTable.class);
        assertEquals(0, t.version());
        assertEquals(0L, t.revision());
    }

    @Test
    void documentedDefaultsApplyWhenAFlagOrPagingNumberIsOmitted() {
        assertFalse(read("{\"messageName\":\"m\"}", ProcessPort.MessageRequest.class).all(),
                "exactly one instance unless all=true");
        assertFalse(read("{\"serviceKey\":\"svc\"}", PortalPort.ApplyRequest.class).submit(), "kept as a draft");
        Resource.Query q = read("{\"typeId\":\"hall\"}", Resource.Query.class);
        assertEquals(0, q.offset());
        assertEquals(0, q.limit(), "0 is the catalogue's page default (50)");
        // a JSON null is the same as leaving the member out
        assertFalse(read("{\"messageName\":\"m\",\"all\":null}", ProcessPort.MessageRequest.class).all());
    }

    @Test
    void anExplicitValueIsKept() {
        AdminPort.OrgUnit o = read("{\"code\":\"C\",\"name\":\"N\",\"active\":false,\"revision\":3}", AdminPort.OrgUnit.class);
        assertEquals(Boolean.FALSE, o.active());
        assertEquals(3L, o.revision());
        assertTrue(read("{\"messageName\":\"m\",\"all\":true}", ProcessPort.MessageRequest.class).all());
        assertTrue(read("{\"serviceKey\":\"svc\",\"submit\":true}", PortalPort.ApplyRequest.class).submit());
        Resource.Query q = read("{\"offset\":20,\"limit\":10}", Resource.Query.class);
        assertEquals(20, q.offset());
        assertEquals(10, q.limit());
        PowerTablePort.PowerTable t = read("{\"key\":\"k\",\"version\":2,\"revision\":4}", PowerTablePort.PowerTable.class);
        assertEquals(2, t.version());
        assertEquals(4L, t.revision());
    }

    @Test
    void aWrongTypeIsStillRefused() {
        assertThrows(MismatchedInputException.class,
                () -> read("{\"messageName\":\"m\",\"all\":\"yes please\"}", ProcessPort.MessageRequest.class));
        assertThrows(MismatchedInputException.class,
                () -> read("{\"code\":\"C\",\"revision\":\"latest\"}", AdminPort.OrgUnit.class));
    }
}
