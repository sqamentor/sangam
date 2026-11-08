// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.bundle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sangam.config.profile.ProfileDocument;
import org.sangam.config.profile.ProfileRepository;
import org.sangam.onboarding.api.OnboardingRequest;
import org.sangam.onboarding.api.OnboardingRequest.ProfileMode;
import org.sangam.onboarding.api.OnboardingRequest.ProfileSpec;
import org.sangam.onboarding.flow.OnboardingException;
import org.sangam.onboarding.support.OnboardingJson;

/**
 * Signed tenant configuration bundles, so one tenant can copy another's set-up.
 *
 * <p>Shape: {@code {"format": 1, "body": {sourceTenant, request, documents, packVersions}, "signature": {keyId,
 * algorithm, value}}}. The body holds configuration only: go-live evidence is dropped (each tenant proves its own), and
 * connector bindings are ids (secrets stay in each deployment's secret resolver).
 */
public final class BundleService {

    public static final int FORMAT = 1;

    private final ProfileRepository profiles;
    private final BundleSigner signer;
    private final boolean allowUnsigned;

    /**
     * @param allowUnsigned accept and produce unsigned bundles (development only)
     */
    public BundleService(ProfileRepository profiles, BundleSigner signer, boolean allowUnsigned) {
        this.profiles = profiles;
        this.signer = signer;
        this.allowUnsigned = allowUnsigned;
    }

    /** Builds and signs the bundle of a tenant from its last request and its stored documents. */
    public Map<String, Object> export(OnboardingRequest request, Map<String, String> packVersions) {
        return export(request, packVersions, null);
    }

    /**
     * As {@link #export(OnboardingRequest, Map)}, labelled with the source environment ({@code sandbox}, {@code uat},
     * ...). The label is inside the signed body, so promotion ({@link PromotionService}) can trust it.
     */
    public Map<String, Object> export(OnboardingRequest request, Map<String, String> packVersions, String environment) {
        String tenant = request.tenantId();
        List<Map<String, Object>> documents = new ArrayList<>();
        for (String id : profiles.ids()) {
            if (!id.equals(tenant) && !id.startsWith(tenant + ".") && !id.startsWith(tenant + "/")) {
                continue;
            }
            profiles.versions(id).stream().filter(s -> s.status() == ProfileRepository.Status.ACTIVE)
                    .map(ProfileRepository.Stored::document)
                    .max((a, b) -> ProfileRepository.compareVersions(a.version(), b.version()))
                    .ifPresent(d -> documents.add(OnboardingJson.decimalsAsStrings(d.toTree())));
        }
        // documents the request brought along that are not under the tenant (a new jurisdiction, for example)
        for (Map<String, Object> d : request.profile().documents()) {
            String id = headerId(d);
            if (documents.stream().noneMatch(x -> id != null && id.equals(headerId(x)))) {
                documents.add(OnboardingJson.decimalsAsStrings(d));
            }
        }
        OnboardingRequest clean = request.withGoLive(new OnboardingRequest.GoLiveSpec(null, request.goLive().languages()))
                .withProfile(request.profile().withDocuments(List.of(), Map.of()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceTenant", tenant);
        body.put("request", OnboardingJson.tree(clean));
        body.put("documents", documents);
        body.put("packVersions", new LinkedHashMap<>(packVersions));
        if (environment != null) {
            body.put("environment", environment);
        }
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("format", FORMAT);
        bundle.put("body", body);
        if (signer.canSign()) {
            Map<String, Object> sig = new LinkedHashMap<>();
            sig.put("keyId", signer.keyId());
            sig.put("algorithm", "Ed25519");
            sig.put("value", signer.sign(OnboardingJson.canonical(body)));
            bundle.put("signature", sig);
        } else if (!allowUnsigned) {
            throw new OnboardingException(409, "BUNDLE_SIGNER_MISSING", "no bundle signing key is configured");
        }
        return bundle;
    }

    /** Verifies the signature; refuses unsigned bundles unless development mode allows them. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> verified(Map<String, Object> bundle) {
        if (!(bundle.get("body") instanceof Map<?, ?> raw) || !Integer.valueOf(FORMAT).equals(bundle.get("format"))) {
            throw new OnboardingException(422, "BUNDLE_INVALID", "not a tenant bundle of format " + FORMAT);
        }
        Map<String, Object> body = (Map<String, Object>) raw;
        if (bundle.get("signature") instanceof Map<?, ?> sig) {
            if (!signer.verify(String.valueOf(sig.get("keyId")), OnboardingJson.canonical(body), String.valueOf(sig.get("value")))) {
                throw new OnboardingException(422, "BUNDLE_SIGNATURE_INVALID",
                        "the signature does not verify against a trusted key (" + sig.get("keyId") + ")");
            }
        } else if (!allowUnsigned) {
            throw new OnboardingException(422, "BUNDLE_UNSIGNED", "unsigned bundles are refused outside development");
        }
        return body;
    }

    /**
     * Turns a verified bundle into a request for another tenant: every document id, {@code extends} and {@code adopt}
     * reference under the source scope is renamed to the target scope.
     *
     * @param targetTenant new tenant id
     * @param name         display name of the new tenant
     * @param names        display names by language
     * @param parentId     control-plane parent (null keeps the source's)
     * @param overrides    profile sections specific to the new tenant, merged on top of its document
     * @param goLive       the new tenant's go-live evidence
     */
    @SuppressWarnings("unchecked")
    public OnboardingRequest toRequest(Map<String, Object> bundle, String targetTenant, String name,
                                       Map<String, String> names, String parentId, Map<String, Object> overrides,
                                       OnboardingRequest.GoLiveSpec goLive) {
        Map<String, Object> body = verified(bundle);
        String source = String.valueOf(body.get("sourceTenant"));
        OnboardingRequest req = OnboardingJson.MAPPER.convertValue(body.get("request"), OnboardingRequest.class);
        List<Map<String, Object>> docs = new ArrayList<>();
        for (Map<String, Object> d : (List<Map<String, Object>>) body.getOrDefault("documents", List.of())) {
            docs.add(rename(d, source, targetTenant, overrides));
        }
        ProfileSpec p = req.profile();
        ProfileSpec spec = new ProfileSpec(p.jurisdiction(), p.regimePreset(), p.parent(), ProfileMode.IMPORT, p.layer(),
                null, p.effectiveFrom(), Map.of(), docs, List.of());
        OnboardingRequest.TenantSpec t = req.tenant().withId(targetTenant, parentId != null ? parentId : req.tenant().parentId())
                .withName(name != null ? name : req.tenant().name(), names != null ? names : Map.of());
        List<Map<String, Object>> seeds = new ArrayList<>();
        for (Map<String, Object> s : req.seeds().classifications()) {
            Map<String, Object> copy = new LinkedHashMap<>(s);
            if (copy.get("scope") != null) {
                copy.put("scope", renameId(String.valueOf(copy.get("scope")), source, targetTenant));
            }
            seeds.add(copy);
        }
        return new OnboardingRequest(t, spec, req.packs(), req.connectors(), req.security(),
                new OnboardingRequest.SeedSpec(seeds, req.seeds().partyIdentifierTypes(), req.seeds().calendars()),
                req.compliance(), req.dmnOverrides(),
                goLive != null ? goLive : new OnboardingRequest.GoLiveSpec(null, req.goLive().languages()));
    }

    /** Difference of two bundles' bodies, with each source tenant id normalised so only real differences show. */
    @SuppressWarnings("unchecked")
    public List<TreeDiff.Entry> diff(Map<String, Object> left, Map<String, Object> right) {
        return TreeDiff.diff(normalise((Map<String, Object>) left.get("body")), normalise((Map<String, Object>) right.get("body")));
    }

    @SuppressWarnings("unchecked")
    private static Object normalise(Map<String, Object> body) {
        if (body == null) {
            throw new OnboardingException(422, "BUNDLE_INVALID", "bundle has no body");
        }
        String source = String.valueOf(body.get("sourceTenant"));
        Map<String, Object> copy = new LinkedHashMap<>(body);
        copy.remove("sourceTenant");
        List<Map<String, Object>> docs = new ArrayList<>();
        for (Map<String, Object> d : (List<Map<String, Object>>) body.getOrDefault("documents", List.of())) {
            docs.add(rename(d, source, "{tenant}", Map.of()));
        }
        copy.put("documents", docs);
        if (copy.get("request") instanceof Map<?, ?> r) {
            Map<String, Object> rq = new LinkedHashMap<>((Map<String, Object>) r);
            if (rq.get("tenant") instanceof Map<?, ?> t) {
                Map<String, Object> tn = new LinkedHashMap<>((Map<String, Object>) t);
                tn.put("id", "{tenant}");
                rq.put("tenant", tn);
            }
            copy.put("request", rq);
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rename(Map<String, Object> doc, String source, String target,
                                              Map<String, Object> overrides) {
        Map<String, Object> d = new LinkedHashMap<>(doc);
        Map<String, Object> h = new LinkedHashMap<>((Map<String, Object>) d.getOrDefault("profile", Map.of()));
        String id = String.valueOf(h.get("id"));
        String newId = renameId(id, source, target);
        h.put("id", newId);
        if (h.get("extends") instanceof List<?> e) {
            h.put("extends", e.stream().map(x -> renameId(String.valueOf(x), source, target)).toList());
        }
        d.put("profile", h);
        if (newId.equals(target) && overrides != null) {
            d.putAll(overrides);
        }
        return d;
    }

    private static String renameId(String id, String source, String target) {
        if (id.equals(source)) {
            return target;
        }
        if (id.startsWith(source + ".") || id.startsWith(source + "/")) {
            return target + id.substring(source.length());
        }
        return id;
    }

    @SuppressWarnings("unchecked")
    private static String headerId(Map<String, Object> d) {
        return d.get("profile") instanceof Map<?, ?> h ? String.valueOf(((Map<String, Object>) h).get("id")) : null;
    }

    /** Parses a stored document tree (used by callers that show what a bundle holds). */
    public static ProfileDocument document(Map<String, Object> tree) {
        return org.sangam.config.profile.ProfileLoader.fromTree(tree);
    }
}
