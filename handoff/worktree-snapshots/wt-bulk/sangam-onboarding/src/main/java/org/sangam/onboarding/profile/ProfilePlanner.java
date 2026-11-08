// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.profile;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sangam.config.common.Finding;
import org.sangam.config.profile.InMemoryProfileRepository;
import org.sangam.config.profile.LayerModel;
import org.sangam.config.profile.ProfileDocument;
import org.sangam.config.profile.ProfileLoader;
import org.sangam.config.profile.ProfileRepository;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.config.profile.ProfileValidator;
import org.sangam.config.profile.ResolvedProfile;
import org.sangam.config.profile.ScopeRef;
import org.sangam.onboarding.api.OnboardingRequest;
import org.sangam.onboarding.api.OnboardingRequest.ProfileMode;
import org.sangam.onboarding.api.OnboardingRequest.ProfileSpec;
import org.sangam.onboarding.api.Problem;
import org.sangam.onboarding.api.StepId;
import org.sangam.onboarding.support.OnboardingJson;

/**
 * Turns a request into the profile documents to store, and resolves them <em>before</em> anything is stored: the
 * preview resolver sees every ACTIVE document plus the planned ones, so the dry run and the readiness check judge
 * exactly what activation would produce.
 */
public final class ProfilePlanner {

    private final ProfileRepository repo;
    private final LayerModel layers;
    private final ProfileValidator validator;

    public ProfilePlanner(ProfileRepository repo, LayerModel layers, ProfileValidator validator) {
        this.repo = repo;
        this.layers = layers;
        this.validator = validator;
    }

    /** The result of planning. */
    public record Plan(List<ProfileDocument> documents, ResolvedProfile resolved, List<Problem> problems) {
    }

    /**
     * @param defaultVersion version of a generated tenant document when the request names none
     * @param today          date the resolution is evaluated on (the later of today and the effective date)
     */
    public Plan plan(OnboardingRequest req, String defaultVersion, LocalDate today) {
        List<Problem> problems = new ArrayList<>();
        List<ProfileDocument> docs = new ArrayList<>();
        ProfileSpec spec = req.profile();
        String tenant = req.tenantId();
        for (Map<String, Object> tree : spec.documents()) {
            try {
                docs.add(ProfileLoader.fromTree(OnboardingJson.decimalsAsStrings(tree)));
            } catch (RuntimeException e) {
                problems.add(Problem.error(StepId.PROFILE, "PROFILE_DOCUMENT_INVALID", String.valueOf(tree.get("profile")),
                        e.getMessage()));
            }
        }
        if (docs.stream().noneMatch(d -> d.id().equals(tenant))) {
            if (spec.mode() == ProfileMode.IMPORT) {
                problems.add(Problem.error(StepId.PROFILE, "PROFILE_DOCUMENT_MISSING", tenant,
                        "IMPORT mode needs a document with the tenant's id"));
            } else {
                try {
                    docs.add(generate(req, defaultVersion, today, docs, problems));
                } catch (RuntimeException e) {
                    problems.add(Problem.error(StepId.PROFILE, "PROFILE_DOCUMENT_INVALID", tenant, e.getMessage()));
                }
            }
        }
        docs.sort(Comparator.comparingInt(d -> layers.rank(d.layer())));
        for (ProfileDocument d : docs) {
            for (Finding f : validator.validateDocument(d)) {
                add(problems, f);
            }
        }
        LocalDate asOf = docs.stream().filter(d -> d.id().equals(tenant)).map(ProfileDocument::effectiveFrom)
                .filter(x -> x != null && x.isAfter(today)).findFirst().orElse(today);
        ResolvedProfile resolved = null;
        try {
            List<ProfileDocument> all = new ArrayList<>();
            for (String id : repo.ids()) {
                repo.versions(id).stream().filter(s -> s.status() == ProfileRepository.Status.ACTIVE)
                        .forEach(s -> all.add(s.document()));
            }
            all.removeIf(a -> docs.stream().anyMatch(d -> d.id().equals(a.id()) && d.version().equals(a.version())));
            all.addAll(docs);
            ProfileResolver preview = new ProfileResolver(InMemoryProfileRepository.active(all), layers);
            resolved = preview.resolve(tenant, asOf);
            for (Finding f : validator.validate(preview, tenant, null, asOf)) {
                add(problems, f);
            }
        } catch (RuntimeException e) {
            problems.add(Problem.error(StepId.PROFILE, "PROFILE_UNRESOLVABLE", tenant, e.getMessage()));
        }
        return new Plan(List.copyOf(docs), resolved, problems);
    }

    private ProfileDocument generate(OnboardingRequest req, String defaultVersion, LocalDate today,
                                     List<ProfileDocument> supplied, List<Problem> problems) {
        ProfileSpec spec = req.profile();
        String tenant = req.tenantId();
        ScopeRef scope = ScopeRef.parse(tenant);
        List<String> prefixes = scope.prefixes().subList(0, scope.prefixes().size() - 1);
        List<String> ext = new ArrayList<>();
        for (String id : new String[]{spec.regimePreset(), spec.jurisdiction(), spec.parent()}) {
            if (id == null || id.isBlank() || prefixes.contains(id)) {
                continue;
            }
            if (!known(id, supplied)) {
                problems.add(Problem.error(StepId.PROFILE, "PROFILE_NOT_FOUND", id, "profile is neither active nor"
                        + " supplied in profile.documents"));
                continue;
            }
            ext.add(id);
        }
        ext.sort(Comparator.comparingInt(id -> layerRank(id, supplied)));
        if (ext.isEmpty() && prefixes.isEmpty() && spec.mode() != ProfileMode.COPY) {
            problems.add(Problem.error(StepId.PROFILE, "JURISDICTION_REQUIRED", tenant,
                    "a root tenant needs profile.jurisdiction, profile.regimePreset or profile.parent"));
        }
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("id", tenant);
        header.put("layer", spec.layer() != null ? spec.layer() : scope.depth() <= 1 ? "government" : "department");
        header.put("version", spec.version() != null ? spec.version() : defaultVersion);
        header.put("effectiveFrom", spec.effectiveFrom() != null ? spec.effectiveFrom() : today.toString());
        header.put("extends", ext);
        if (spec.mode() == ProfileMode.COPY) {
            header.put("adopt", spec.adoptFrom());
        }
        Map<String, Object> names = new LinkedHashMap<>(req.tenant().names());
        if (req.tenant().name() != null) {
            names.putIfAbsent("en", req.tenant().name());
        }
        header.put("name", names);
        header.put("source", "onboarding");
        Map<String, Object> tree = new LinkedHashMap<>(OnboardingJson.decimalsAsStrings(new LinkedHashMap<>(spec.overrides())));
        tree.put("profile", header);
        return ProfileLoader.fromTree(tree);
    }

    private boolean known(String id, List<ProfileDocument> supplied) {
        return supplied.stream().anyMatch(d -> d.id().equals(id)) || repo.versions(id).stream()
                .anyMatch(s -> s.status() == ProfileRepository.Status.ACTIVE);
    }

    private int layerRank(String id, List<ProfileDocument> supplied) {
        for (ProfileDocument d : supplied) {
            if (d.id().equals(id)) {
                return layers.rank(d.layer());
            }
        }
        return repo.versions(id).stream().findFirst().map(s -> layers.rank(s.document().layer())).orElse(-1);
    }

    private static void add(List<Problem> out, Finding f) {
        switch (f.severity()) {
            case ERROR -> out.add(Problem.error(StepId.PROFILE, f.code(), f.path(), f.message()));
            case WARNING -> out.add(Problem.warn(StepId.PROFILE, f.code(), f.path(), f.message()));
            default -> { // placeholders are judged by the readiness check against what the enabled packs read
            }
        }
    }
}
