// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.readiness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sangam.config.profile.ResolvedProfile;
import org.sangam.onboarding.api.OnboardingRequest;
import org.sangam.onboarding.api.OnboardingRequest.ConnectorBindingSpec;
import org.sangam.onboarding.api.OnboardingRun.ReadinessReport;
import org.sangam.onboarding.api.Problem;
import org.sangam.onboarding.api.StepId;
import org.sangam.onboarding.deps.PackDependencyResolver;
import org.sangam.onboarding.port.OnboardingPorts;
import org.sangam.onboarding.support.OnboardingJson;
import org.sangam.packs.spi.SangamPack;

/**
 * The go-live checklist. Read-only, and it evaluates the planned state, so the dry run and the READINESS step give the
 * same answer. Checks: profile resolves; required profile keys present; no placeholder used by an enabled pack; every
 * pack connector bound; master key and signer configured; DMN overrides valid; languages available; go-live evidence.
 */
public final class ReadinessChecker {

    private final OnboardingPorts.ConnectorPort connectors;
    private final OnboardingPorts.SecurityPort security;
    private final OnboardingPorts.LanguagePort languages;
    private final OnboardingPorts.TenantPort tenants;
    private final DmnOverrideValidator dmn = new DmnOverrideValidator();

    public ReadinessChecker(OnboardingPorts.ConnectorPort connectors, OnboardingPorts.SecurityPort security,
                            OnboardingPorts.LanguagePort languages, OnboardingPorts.TenantPort tenants) {
        this.connectors = connectors;
        this.security = security;
        this.languages = languages;
        this.tenants = tenants;
    }

    /**
     * @param earlier problems of the earlier steps; their errors block go-live too
     */
    public ReadinessReport check(OnboardingRequest req, ResolvedProfile profile, PackDependencyResolver.Resolution packs,
                                 List<SangamPack> enabled, List<Problem> earlier, String requestHash, int revision,
                                 Instant now) {
        Map<String, String> checks = new LinkedHashMap<>();
        List<Problem> out = new ArrayList<>();
        section(checks, "earlier-steps", out, earlier.stream().filter(Problem::isError).toList());
        section(checks, "profile-keys", out, profileKeys(profile, packs));
        section(checks, "placeholders", out, placeholders(profile, packs));
        section(checks, "connectors", out, connectorChecks(req, packs));
        section(checks, "keys", out, keys());
        section(checks, "dmn-overrides", out, dmn.validate(req.dmnOverrides(), enabled));
        section(checks, "languages", out, languageChecks(req, profile));
        section(checks, "go-live-evidence", out, evidence(req));
        boolean ready = !Problem.anyError(out);
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("requestHash", requestHash);
        basis.put("revision", revision);
        basis.put("checks", checks);
        basis.put("profileBinding", profile == null ? null : profile.binding());
        basis.put("problems", OnboardingJson.MAPPER.convertValue(out, List.class));
        return new ReadinessReport(ready, out, checks, OnboardingJson.hash(basis), now, revision);
    }

    private static void section(Map<String, String> checks, String name, List<Problem> out, List<Problem> found) {
        out.addAll(found);
        checks.put(name, Problem.anyError(found) ? "FAIL" : found.isEmpty() ? "PASS" : "WARN");
    }

    private static List<Problem> profileKeys(ResolvedProfile p, PackDependencyResolver.Resolution packs) {
        List<Problem> out = new ArrayList<>();
        if (p == null) {
            out.add(Problem.error(StepId.READINESS, "PROFILE_UNRESOLVED", "profile", "the tenant profile did not resolve"));
            return out;
        }
        packs.profileKeys().forEach((key, users) -> {
            if (p.get(key) == null) {
                out.add(Problem.error(StepId.READINESS, "PROFILE_KEY_MISSING", key, "required by " + users
                        + " but absent from the resolved profile; add it to profile.overrides"));
            }
        });
        return out;
    }

    private static List<Problem> placeholders(ResolvedProfile p, PackDependencyResolver.Resolution packs) {
        List<Problem> out = new ArrayList<>();
        if (p == null) {
            return out;
        }
        p.placeholders().forEach((path, reason) -> {
            Set<String> users = new LinkedHashSet<>();
            packs.profileKeys().forEach((key, packIds) -> {
                if (path.equals(key) || path.startsWith(key + ".") || key.startsWith(path + ".")) {
                    users.addAll(packIds);
                }
            });
            if (users.isEmpty()) {
                out.add(Problem.warn(StepId.READINESS, "PLACEHOLDER_UNUSED", path, reason));
            } else {
                out.add(Problem.error(StepId.READINESS, "PLACEHOLDER_USED", path, "used by " + users + ": " + reason
                        + "; set the confirmed value in profile.overrides"));
            }
        });
        return out;
    }

    private List<Problem> connectorChecks(OnboardingRequest req, PackDependencyResolver.Resolution packs) {
        List<Problem> out = new ArrayList<>();
        Set<String> requested = new LinkedHashSet<>();
        for (ConnectorBindingSpec b : req.connectors()) {
            requested.add(b.packConnector());
        }
        packs.connectors().forEach((id, users) -> {
            if (requested.contains(id) || connectors.boundTo(req.tenantId(), id).isPresent()) {
                return;
            }
            String msg = "pack connector used by " + users + " has no binding; add it to connectors";
            if (connectors.simulatorFallback()) {
                out.add(Problem.warn(StepId.READINESS, "CONNECTOR_SIMULATOR_ONLY", id, msg + " (development simulator serves it now)"));
            } else {
                out.add(Problem.error(StepId.READINESS, "CONNECTOR_UNBOUND", id, msg));
            }
        });
        return out;
    }

    private List<Problem> keys() {
        List<Problem> out = new ArrayList<>();
        if (!security.masterKeyConfigured()) {
            out.add(Problem.error(StepId.READINESS, "MASTER_KEY_MISSING", "sangam.security.kek",
                    "no key-encryption key is configured (the ephemeral development key does not count)"));
        }
        if (!security.signerConfigured()) {
            out.add(Problem.error(StepId.READINESS, "SIGNER_MISSING", "signer", "no signing key is configured"));
        }
        return out;
    }

    private List<Problem> languageChecks(OnboardingRequest req, ResolvedProfile p) {
        List<Problem> out = new ArrayList<>();
        Set<String> have = languages.available();
        for (String tag : req.goLive().languages()) {
            if (!have.contains(tag)) {
                out.add(Problem.error(StepId.READINESS, "LANGUAGE_UNAVAILABLE", tag, "requested for go-live; no language"
                        + " catalogue is installed for it (add one through a plugin or sangam.onboarding.languages)"));
            }
        }
        if (p != null) {
            for (Map<String, Object> l : p.list("locale.languages")) {
                String tag = String.valueOf(l.get("tag"));
                if (have.contains(tag)) {
                    continue;
                }
                if (Boolean.TRUE.equals(l.get("default")) || "true".equals(String.valueOf(l.get("default")))) {
                    out.add(Problem.error(StepId.READINESS, "LANGUAGE_UNAVAILABLE", tag, "default language of the"
                            + " profile; no language catalogue is installed for it"));
                } else {
                    out.add(Problem.warn(StepId.READINESS, "LANGUAGE_UNAVAILABLE", tag, "profile language without an"
                            + " installed catalogue; users see the default language"));
                }
            }
        }
        return out;
    }

    private List<Problem> evidence(OnboardingRequest req) {
        List<Problem> out = new ArrayList<>();
        List<String> missing;
        try {
            missing = tenants.missingGoLiveEvidence(req.tenantId(), req.compliance().jurisdictions());
        } catch (RuntimeException e) {
            out.add(Problem.error(StepId.READINESS, "GO_LIVE_EVIDENCE_UNKNOWN", req.tenantId(), String.valueOf(e.getMessage())));
            return out;
        }
        for (String item : missing) {
            if (req.goLive().evidence().get(item) == null) {
                out.add(Problem.error(StepId.READINESS, "GO_LIVE_EVIDENCE_MISSING", item,
                        "the tenant directory requires evidence for this item; add goLive.evidence." + item));
            }
        }
        return out;
    }
}
