// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.onboarding.api.Problem;
import org.sangam.onboarding.bundle.BundleSigner;
import org.sangam.onboarding.bundle.TreeDiff;
import org.sangam.onboarding.deps.PackDependencyResolver;
import org.sangam.onboarding.deps.PackRequirementsCatalog;
import org.sangam.onboarding.deps.PlatformInfo;
import org.sangam.onboarding.deps.SemVer;
import org.sangam.onboarding.deps.VersionRange;
import org.sangam.packs.spi.PackRegistry;

/** Fast, pure tests: versions, ranges, resolution over the shipped packs, bundle signatures, diff. */
class DependencyResolutionTest {

    private static PackDependencyResolver resolver(String engine) {
        return new PackDependencyResolver(PackRegistry.discover(), PackRequirementsCatalog.scan(DependencyResolutionTest.class.getClassLoader()),
                new PlatformInfo("0.1.0-SNAPSHOT", engine, Map.of("sangam-config", "0.1.0-SNAPSHOT", "sangam-ledger", "0.1.0-SNAPSHOT")));
    }

    private static boolean has(PackDependencyResolver.Resolution r, String code) {
        return r.problems().stream().anyMatch(p -> p.code().equals(code));
    }

    @Test
    void precedenceAndRanges() {
        assertTrue(SemVer.parse("1.0.0-alpha").compareTo(SemVer.parse("1.0.0")) < 0);
        assertTrue(SemVer.parse("2.2").equals(SemVer.parse("2.2.0")));
        assertTrue(VersionRange.parse("^1.2.3").contains("1.9.0"));
        assertFalse(VersionRange.parse("^1.2.3").contains("2.0.0"));
        assertFalse(VersionRange.parse("^0.2.3").contains("0.3.0"));
        assertTrue(VersionRange.parse("~1.2.3").contains("1.2.9"));
        assertTrue(VersionRange.parse("1.x || >=3.0.0").contains("3.1.0"));
        assertTrue(VersionRange.parse(">= 2.2.0 <3.0.0").contains("2.2.0"));
        assertTrue(VersionRange.parse(">=0.1.0-0").contains("0.1.0-SNAPSHOT"));
        assertFalse(VersionRange.parse(">=0.1.0").contains("0.1.0-SNAPSHOT"));
    }

    @Test
    void addsDependenciesFirst() {
        var r = resolver("2.2.0").resolve(List.of("gov.ifms.bill"), Map.of(), Map.of(), false);
        assertTrue(r.ok(), r.problems()::toString);
        assertTrue(r.order().indexOf("sangam.core") < r.order().indexOf("gov.ifms.bill"));
        assertTrue(r.addedDependencies().contains("gov.ifms.pao-chain"));
        assertTrue(r.connectors().containsKey("pfms.payment"));
        assertTrue(r.profileKeys().containsKey("documentTypes"));
    }

    @Test
    void reportsEveryProblem() {
        var r = resolver("3.0.0").resolve(List.of("no.such.pack", "gov.ifms.bill"), Map.of("gov.ifms.bill", "^9.0.0"), Map.of(), false);
        assertTrue(has(r, "PACK_NOT_FOUND"));
        assertTrue(has(r, "ENGINE_INCOMPATIBLE"));
        assertTrue(has(r, "PACK_VERSION_UNSATISFIED"));
        assertTrue(Problem.anyError(r.problems()));
    }

    @Test
    void upgradeRules() {
        var res = resolver("2.2.0");
        assertTrue(has(res.resolve(List.of("sangam.core"), Map.of(), Map.of("sangam.core", "0.9.0"), false), "UPGRADE_NOT_ALLOWED"));
        assertTrue(has(res.resolve(List.of("sangam.core"), Map.of(), Map.of("sangam.core", "0.9.0"), true), "UPGRADE_PATH_MISSING"));
        assertTrue(has(res.resolve(List.of("sangam.core"), Map.of(), Map.of("sangam.core", "9.0.0"), true), "DOWNGRADE_REFUSED"));
    }

    @Test
    void bundleSignatureAndDiff() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        BundleSigner s = new BundleSigner("k1", Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()),
                Map.of("k1", Base64.getEncoder().encodeToString(kp.getPublic().getEncoded())));
        String sig = s.sign("{\"a\":1}");
        assertTrue(s.verify("k1", "{\"a\":1}", sig));
        assertFalse(s.verify("k1", "{\"a\":2}", sig));
        assertFalse(s.verify("other", "{\"a\":1}", sig));
        var d = TreeDiff.diff(Map.of("a", 1, "b", List.of(1)), Map.of("a", 2, "c", 3, "b", List.of(1)));
        assertEquals(2, d.stream().filter(e -> e.kind() != TreeDiff.Kind.REMOVED).count());
    }
}
