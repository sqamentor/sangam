// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.config.catalogue.NumberSeries;
import org.sangam.config.locale.ProfileBusinessClock;
import org.sangam.config.master.ClassificationRegistry;
import org.sangam.config.master.MasterStore;
import org.sangam.config.profile.InMemoryProfileRepository;
import org.sangam.config.profile.LayerModel;
import org.sangam.config.profile.ProfileDocument;
import org.sangam.config.profile.ProfileRepository;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;

/**
 * SL-119: a call of a bound case reads the profile versions of the case's binding; a case with no stored binding, or a
 * call naming no case, reads the versions in force; a binding that no longer resolves fails the call, never falling
 * back to today's versions.
 */
class ConfigConnectorPinTest {

    static final LocalDate FROM = LocalDate.of(2026, 4, 1);
    static final LocalDate AS_OF = LocalDate.of(2026, 11, 5);

    static ProfileDocument zz(String version, List<Map<String, Object>> types) {
        return new ProfileDocument("ZZ", "government", version, FROM, null, List.of(), List.of(), Map.of(), "test",
                Map.of(), List.of(), Map.of("documentTypes", types,
                        "approvalChains", List.of(Map.of("id", "STD", "steps", List.of(Map.of("role", "DDO"))))));
    }

    static Map<String, Object> type(String id, String form) {
        return Map.of("id", id, "formNumber", form, "approvalChain", "STD", "sanction", "REFERENCE");
    }

    static ConnectorRequest request(String root, String op, Map<String, Object> input) {
        Map<String, Object> in = new LinkedHashMap<>(input);
        in.put("asOf", AS_OF.toString());
        return new ConnectorRequest("ZZ", UUID.randomUUID(), "a", 1, root, null, op, in, null);
    }

    @Test
    void aBoundCaseKeepsItsVersionAndNewOrUnboundCallsReadTheCurrentOne() {
        InMemoryProfileRepository repo = new InMemoryProfileRepository();
        repo.save(zz("1", List.of(type("ZZ_FVC", "FVC"))), ProfileRepository.Status.ACTIVE);
        ProfileResolver resolver = new ProfileResolver(repo, LayerModel.open());
        String v1 = resolver.resolve("ZZ", AS_OF).binding();
        Map<String, String> stored = new LinkedHashMap<>(Map.of("ROOT-OLD", v1, "ROOT-GONE", v1.replace("ZZ@1", "ZZ@7")));
        ConfigConnector c = new ConfigConnector(resolver, new ClassificationRegistry(new MasterStore.InMemory()),
                new NumberSeries.InMemory(), new ProfileBusinessClock(resolver, Clock.systemUTC()),
                (tenant, root) -> "ZZ".equals(tenant) ? Optional.ofNullable(stored.get(root)) : Optional.empty());

        repo.save(zz("2", List.of(type("ZZ_FVC", "FVC"), type("ZZ_NEW", "NEW-1"))), ProfileRepository.Status.ACTIVE);
        resolver.invalidate();
        String v2 = resolver.resolve("ZZ", AS_OF).binding();

        Map<String, Object> newForm = Map.of("typeRef", "NEW-1");
        assertInstanceOf(ConnectorResult.BusinessFailure.class, c.invoke(request("ROOT-OLD", "documentType.get", newForm)),
                "the running case keeps its catalogue");
        ConnectorResult.Success old = assertInstanceOf(ConnectorResult.Success.class,
                c.invoke(request("ROOT-OLD", "documentType.check", Map.of("typeId", "ZZ_NEW"))));
        assertEquals(Boolean.FALSE, old.output().get("valid"), "check against the bound catalogue too");
        assertEquals(v1, ((ConnectorResult.Success) c.invoke(request("ROOT-OLD", "profile.binding", Map.of())))
                .output().get("binding"));

        ConnectorResult.Success unbound = assertInstanceOf(ConnectorResult.Success.class,
                c.invoke(request("ROOT-NEW", "documentType.get", newForm)), "a case without a stored binding: current");
        assertEquals("ZZ_NEW", unbound.output().get("id"));
        assertInstanceOf(ConnectorResult.Success.class, c.invoke(request(null, "documentType.get", newForm)),
                "no case at all: current");
        assertEquals(v2, ((ConnectorResult.Success) c.invoke(request(null, "profile.binding", Map.of())))
                .output().get("binding"));

        ConnectorResult.BusinessFailure gone = assertInstanceOf(ConnectorResult.BusinessFailure.class,
                c.invoke(request("ROOT-GONE", "documentType.get", Map.of("typeRef", "FVC"))));
        assertEquals("BINDING_UNRESOLVABLE", gone.code(), "never today's versions in place of the case's own");
    }
}
