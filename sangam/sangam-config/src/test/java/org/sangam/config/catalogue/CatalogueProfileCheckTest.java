// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.catalogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.config.admin.ConfigAdminService;
import org.sangam.config.api.ConfigConnector;
import org.sangam.config.common.ConfigException;
import org.sangam.config.common.Finding;
import org.sangam.config.master.ClassificationRegistry;
import org.sangam.config.master.MasterStore;
import org.sangam.config.profile.InMemoryProfileRepository;
import org.sangam.config.profile.LayerModel;
import org.sangam.config.profile.ProfileDocument;
import org.sangam.config.profile.ProfileRepository;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.config.profile.ProfileValidator;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;

/**
 * SL-35: the catalogue's integrity check is the profile linter's "document-type chains" check. A profile version whose
 * new document type names an approval chain the catalogue does not define is refused at activation (the running pack
 * would otherwise fail its chain step with {@code CHAIN_UNKNOWN}); a sound new type is adopted by the very next
 * {@code config.documentType} call, without any pack redeploy.
 */
class CatalogueProfileCheckTest {

    static final LocalDate FROM = LocalDate.of(2026, 4, 1);
    static final LocalDate AS_OF = LocalDate.of(2026, 11, 5);

    static Map<String, Object> type(String id, String form, String chain) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", id);
        t.put("formNumber", form);
        t.put("family", "CONTINGENT");
        t.put("approvalChain", chain);
        t.put("sanction", "REFERENCE");
        return t;
    }

    static ProfileDocument profile(String version, List<Map<String, Object>> types) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentTypes", types);
        data.put("approvalChains", List.of(Map.of("id", "TREASURY_STD", "steps", List.of(Map.of("role", "DDO")))));
        return new ProfileDocument("ZZ", "government", version, FROM, null, List.of(), List.of(), Map.of("en", "Test"),
                "test", Map.of(), List.of(), data);
    }

    static ConfigAdminService admin(ProfileRepository repo, ProfileResolver resolver, ProfileValidator validator) {
        return new ConfigAdminService(repo, resolver, validator, new ConfigAdminService.InMemoryChangeLog(),
                Clock.fixed(AS_OF.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    static void approve(ConfigAdminService admin, ProfileRepository repo, ProfileDocument d) {
        admin.saveDraft(d, "maker");
        admin.submit(d.id(), d.version(), "maker");
        admin.approve(d.id(), d.version(), "checker", repo.get(d.id(), d.version()).orElseThrow().hash());
    }

    static ConnectorResult get(ConfigConnector c, String typeRef) {
        Map<String, Object> in = new LinkedHashMap<>(Map.of("typeRef", typeRef, "asOf", AS_OF.toString()));
        return c.invoke(new ConnectorRequest("ZZ", UUID.randomUUID(), "a", 1, null, null, "documentType.get", in, null));
    }

    @Test
    void profileCheckReportsATypeWhoseChainIsNotDefined() {
        ProfileResolver r = new ProfileResolver(InMemoryProfileRepository.active(List.of(profile("1",
                List.of(type("ZZ_FVC", "FVC", "TREASURY_STD"), type("ZZ_NEW", "NEW-1", "NO_SUCH_CHAIN"))))), LayerModel.open());
        List<Finding> f = DocumentTypeCatalogue.profileCheck(r.resolve("ZZ", AS_OF));
        assertEquals(List.of("CHAIN_UNKNOWN"), Finding.errors(f).stream().map(Finding::code).toList(), String.valueOf(f));
        assertEquals("documentTypes.ZZ_NEW", Finding.errors(f).get(0).path());
        ProfileValidator v = new ProfileValidator(LayerModel.open()).with(DocumentTypeCatalogue::profileCheck);
        assertTrue(Finding.anyError(v.validate(r, "ZZ", null, AS_OF)), "the validator carries the catalogue check");
    }

    @Test
    void activationRefusesABrokenNewTypeAndAdoptsASoundOneWithoutRedeploy() {
        InMemoryProfileRepository repo = new InMemoryProfileRepository();
        repo.save(profile("1", List.of(type("ZZ_FVC", "FVC", "TREASURY_STD"))), ProfileRepository.Status.ACTIVE);
        ProfileResolver resolver = new ProfileResolver(repo, LayerModel.open());
        ProfileValidator validator = new ProfileValidator(LayerModel.open()).with(DocumentTypeCatalogue::profileCheck);
        ConfigAdminService admin = admin(repo, resolver, validator);
        ConfigConnector connector = new ConfigConnector(resolver, new ClassificationRegistry(new MasterStore.InMemory()),
                new org.sangam.config.catalogue.NumberSeries.InMemory());
        assertInstanceOf(ConnectorResult.BusinessFailure.class, get(connector, "NEW-1"), "not in the catalogue yet");

        // a new version whose new type names an undefined chain: refused, the tenant keeps version 1
        List<Map<String, Object>> broken = new ArrayList<>(List.of(type("ZZ_FVC", "FVC", "TREASURY_STD"),
                type("ZZ_NEW", "NEW-1", "NO_SUCH_CHAIN")));
        approve(admin, repo, profile("2", broken));
        ConfigException e = assertThrows(ConfigException.class, () -> admin.activate("ZZ", "2", "admin", List.of("ZZ")));
        assertEquals("ACTIVATION_REFUSED", e.code());
        assertTrue(e.findings().stream().anyMatch(x -> x.code().equals("CHAIN_UNKNOWN")), String.valueOf(e.findings()));
        assertEquals(ProfileRepository.Status.APPROVED, repo.get("ZZ", "2").orElseThrow().status());
        assertInstanceOf(ConnectorResult.BusinessFailure.class, get(connector, "NEW-1"), "refused version not adopted");

        // the corrected version activates and the next catalogue call adopts the new type (same connector instance)
        approve(admin, repo, profile("3", List.of(type("ZZ_FVC", "FVC", "TREASURY_STD"),
                type("ZZ_NEW", "NEW-1", "TREASURY_STD"))));
        admin.activate("ZZ", "3", "admin", List.of("ZZ"));
        ConnectorResult.Success ok = assertInstanceOf(ConnectorResult.Success.class, get(connector, "NEW-1"));
        assertEquals("ZZ_NEW", ok.output().get("id"));
        assertEquals("TREASURY_STD", ok.output().get("approvalChain"));
    }
}
