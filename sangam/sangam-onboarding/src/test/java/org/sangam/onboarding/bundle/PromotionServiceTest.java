// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.bundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.config.profile.InMemoryProfileRepository;
import org.sangam.onboarding.api.DryRunReport;
import org.sangam.onboarding.api.OnboardingRequest;
import org.sangam.onboarding.api.Problem;
import org.sangam.onboarding.api.StepId;
import org.sangam.onboarding.flow.OnboardingException;
import org.sangam.onboarding.support.OnboardingJson;

/** SL-42: sandbox-to-production promotion with a signed bundle, dry run, diff and hash-bound apply. */
class PromotionServiceTest {

    private BundleService sandbox;
    private BundleService prod;
    private BundleService untrusting;
    private final List<OnboardingRequest> dryRuns = new ArrayList<>();
    private final List<String> started = new ArrayList<>();
    private List<Problem> dryRunProblems = List.of();

    @BeforeEach
    void keys() throws Exception {
        KeyPair sbx = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair prd = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String sbxPub = Base64.getEncoder().encodeToString(sbx.getPublic().getEncoded());
        String prdPub = Base64.getEncoder().encodeToString(prd.getPublic().getEncoded());
        sandbox = new BundleService(new InMemoryProfileRepository(),
                new BundleSigner("sandbox-1", Base64.getEncoder().encodeToString(sbx.getPrivate().getEncoded()), Map.of()), false);
        // production trusts the sandbox key and its own
        prod = new BundleService(new InMemoryProfileRepository(),
                new BundleSigner("prod-1", Base64.getEncoder().encodeToString(prd.getPrivate().getEncoded()),
                        Map.of("sandbox-1", sbxPub, "prod-1", prdPub)), false);
        untrusting = new BundleService(new InMemoryProfileRepository(),
                new BundleSigner("prod-1", Base64.getEncoder().encodeToString(prd.getPrivate().getEncoded()),
                        Map.of("prod-1", prdPub)), false);
    }

    private PromotionService promotion(BundleService target) {
        return new PromotionService(target, r -> {
            dryRuns.add(r);
            return new DryRunReport(r.tenantId(), !Problem.anyError(dryRunProblems), dryRunProblems, Map.of(), null,
                    OnboardingJson.hash(r));
        }, (r, maker) -> {
            started.add(r.tenantId() + " by " + maker);
            return null;
        });
    }

    private static OnboardingRequest request(String tenant, String name, String approvalLimit, List<String> packs) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("profile", Map.of("id", tenant, "version", "1", "layer", "department"));
        doc.put("approvals", Map.of("limit", approvalLimit));
        return new OnboardingRequest(
                new OnboardingRequest.TenantSpec(tenant, name, Map.of(), "state-1", null, null, null, null, null, null, null),
                new OnboardingRequest.ProfileSpec(null, null, null, OnboardingRequest.ProfileMode.IMPORT, null, null, null,
                        null, List.of(doc), null),
                new OnboardingRequest.PackSpec(packs, Map.of(), false), null, null, null, null, null, null);
    }

    @Test
    void dryRunShowsTheDiffAndApplyNeedsTheReviewedHash() {
        Map<String, Object> current = prod.export(request("dept-a", "Department A", "100000", List.of("gov.bill")),
                Map.of("gov.bill", "1.0.0"), "prod");
        Map<String, Object> incoming = sandbox.export(request("dept-a-sbx", "Sandbox copy", "250000",
                List.of("gov.bill", "gov.grant")), Map.of("gov.bill", "1.1.0", "gov.grant", "1.0.0"), "sandbox");

        PromotionService.Plan plan = promotion(prod).plan(incoming, current, "dept-a", "prod");
        assertEquals("sandbox", plan.sourceEnvironment());
        assertEquals("dept-a", plan.request().tenantId());
        assertEquals("Department A", plan.request().tenant().name(), "the target keeps its own name");
        assertEquals("dept-a", plan.request().profile().documents().get(0).get("profile") instanceof Map<?, ?> h
                ? h.get("id") : null, "document ids are renamed to the target");
        List<String> paths = plan.changes().stream().map(e -> e.kind() + " " + e.path()).sorted().toList();
        assertTrue(paths.contains("CHANGED documents[profile:{tenant}].approvals.limit"), paths.toString());
        assertTrue(paths.contains("ADDED packVersions.gov.grant"), paths.toString());
        assertTrue(paths.contains("CHANGED packVersions.gov.bill"), paths.toString());
        assertTrue(paths.stream().noneMatch(p -> p.contains("environment") || p.contains("sourceTenant")), paths.toString());
        assertEquals(1, dryRuns.size(), "the plan ran the onboarding dry run");
        assertTrue(started.isEmpty(), "planning writes nothing");

        assertEquals("PROMOTION_PLAN_CHANGED", assertThrows(OnboardingException.class,
                () -> promotion(prod).promote(incoming, current, "dept-a", "prod", "wrong-hash", "maker-1")).code());
        // the target changed after review: the old hash no longer applies
        Map<String, Object> changedTarget = prod.export(request("dept-a", "Department A", "120000", List.of("gov.bill")),
                Map.of("gov.bill", "1.0.0"), "prod");
        assertNotEquals(plan.planHash(), promotion(prod).plan(incoming, changedTarget, "dept-a", "prod").planHash());
        assertEquals("PROMOTION_PLAN_CHANGED", assertThrows(OnboardingException.class,
                () -> promotion(prod).promote(incoming, changedTarget, "dept-a", "prod", plan.planHash(), "maker-1")).code());

        promotion(prod).promote(incoming, current, "dept-a", "prod", plan.planHash(), "maker-1");
        assertEquals(List.of("dept-a by maker-1"), started, "apply starts the maker-checker onboarding run");
    }

    @Test
    void untrustedTamperedOrSameEnvironmentBundlesAreRefused() {
        Map<String, Object> incoming = sandbox.export(request("s", "S", "1", List.of()), Map.of(), "sandbox");
        assertEquals("BUNDLE_SIGNATURE_INVALID", assertThrows(OnboardingException.class,
                () -> promotion(untrusting).plan(incoming, null, "t", "prod")).code());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = new LinkedHashMap<>((Map<String, Object>) incoming.get("body"));
        body.put("environment", "uat");
        Map<String, Object> tampered = new LinkedHashMap<>(incoming);
        tampered.put("body", body);
        assertEquals("BUNDLE_SIGNATURE_INVALID", assertThrows(OnboardingException.class,
                () -> promotion(prod).plan(tampered, null, "t", "prod")).code(), "the environment label is signed");

        Map<String, Object> fromProd = prod.export(request("s", "S", "1", List.of()), Map.of(), "prod");
        assertEquals("PROMOTION_SAME_ENVIRONMENT", assertThrows(OnboardingException.class,
                () -> promotion(prod).plan(fromProd, null, "t", "prod")).code());
        Map<String, Object> unlabelled = sandbox.export(request("s", "S", "1", List.of()), Map.of());
        assertEquals("PROMOTION_ENVIRONMENT_MISSING", assertThrows(OnboardingException.class,
                () -> promotion(prod).plan(unlabelled, null, "t", "prod")).code());
        Map<String, Object> otherTenant = prod.export(request("x", "X", "1", List.of()), Map.of(), "prod");
        assertEquals("PROMOTION_TARGET_MISMATCH", assertThrows(OnboardingException.class,
                () -> promotion(prod).plan(incoming, otherTenant, "t", "prod")).code());
    }

    @Test
    void failedDryRunAndNoChangeBlockTheApply() {
        Map<String, Object> current = prod.export(request("d", "D", "5", List.of("p")), Map.of("p", "1"), "prod");
        Map<String, Object> same = sandbox.export(request("d-sbx", "D", "5", List.of("p")), Map.of("p", "1"), "sandbox");
        PromotionService.Plan noChange = promotion(prod).plan(same, current, "d", "prod");
        assertTrue(noChange.changes().isEmpty(), noChange.changes().toString());
        assertEquals("PROMOTION_NO_CHANGE", assertThrows(OnboardingException.class,
                () -> promotion(prod).promote(same, current, "d", "prod", noChange.planHash(), "m")).code());

        Map<String, Object> changed = sandbox.export(request("d-sbx", "D", "6", List.of("p")), Map.of("p", "1"), "sandbox");
        dryRunProblems = List.of(Problem.error(StepId.values()[0], "CONNECTOR_UNBOUND", "c1", "bind c1"));
        PromotionService.Plan bad = promotion(prod).plan(changed, current, "d", "prod");
        assertEquals("PROMOTION_DRY_RUN_FAILED", assertThrows(OnboardingException.class,
                () -> promotion(prod).promote(changed, current, "d", "prod", bad.planHash(), "m")).code());
        assertTrue(started.isEmpty());
    }

    @Test
    void keyedListsMatchByKeySoReorderingIsNoChange() {
        var a = List.of(Map.of("id", "x", "v", 1), Map.of("id", "y", "v", 2));
        var b = List.of(Map.of("id", "y", "v", 2), Map.of("id", "x", "v", 3));
        List<TreeDiff.Entry> d = TreeDiff.diff(Map.of("l", a), Map.of("l", b));
        assertEquals(1, d.size(), d.toString());
        assertEquals("l[x].v", d.get(0).path());
    }
}
