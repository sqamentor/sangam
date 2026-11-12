// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.time.Period;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RegistryPort;
import org.sangam.rest.port.RequestContext;

/** The daily retention run reads its periods from the tenant's retention-classes registry entries (M-071). Written, not run (Wave 3). */
class ChannelsRetentionPolicyTest {

    @Test
    void periodsComeFromRetentionYearsAndABadEntryIsKept() {
        List<String> asked = new ArrayList<>();
        Map<String, Object> bad = new HashMap<>();
        bad.put("key", "broken");
        bad.put("retentionYears", "zero");
        Map<String, Object> none = new HashMap<>();
        none.put("key", "forever");
        RegistryPort registry = (RegistryPort) Proxy.newProxyInstance(RegistryPort.class.getClassLoader(), new Class<?>[] {RegistryPort.class},
                (p, m, a) -> {
                    RequestContext ctx = (RequestContext) a[0];
                    asked.add(ctx.tenantId() + "/" + a[1]);
                    return Page.of(List.of(Map.of("key", "SHORT", "retentionYears", 1), Map.of("key", "standard", "retentionYears", "7"), bad, none),
                            4, false);
                });
        assertEquals(Map.of("SHORT", Period.ofYears(1), "standard", Period.ofYears(7)), ChannelsWiring.retentionPolicy(registry, "t"),
                "an unreadable entry and one without retentionYears are kept, never purged");
        assertEquals(List.of("t/retention-classes"), asked, "only the tenant's own entries");
    }
}
