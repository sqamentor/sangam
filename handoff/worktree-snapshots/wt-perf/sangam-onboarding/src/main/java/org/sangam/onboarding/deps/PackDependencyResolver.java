// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.deps;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sangam.onboarding.api.Problem;
import org.sangam.onboarding.api.StepId;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;

/**
 * Wraps {@link PackRegistry} with what deployment ordering alone does not check: the dependency closure with semantic
 * version ranges, platform / engine / module compatibility, conflicts (also against what the tenant already has) and
 * upgrade paths. It reports every problem instead of stopping at the first one, so a dry run is complete.
 */
public final class PackDependencyResolver {

    /**
     * @param packId    pack
     * @param from      installed version
     * @param to        available version
     * @param migration declared migration note
     * @param breaking  declared breaking (or a major version change)
     */
    public record UpgradeStep(String packId, String from, String to, String migration, boolean breaking) {
    }

    /**
     * @param order            pack ids, dependencies first
     * @param versions         pack id to the available version
     * @param addedDependencies ids pulled in automatically
     * @param connectors       required connector id to the packs needing it
     * @param profileKeys      required profile path to the packs reading it
     * @param upgrades         upgrades this selection implies
     * @param problems         everything found
     */
    public record Resolution(List<String> order, Map<String, String> versions, List<String> addedDependencies,
                             Map<String, Set<String>> connectors, Map<String, Set<String>> profileKeys,
                             List<UpgradeStep> upgrades, List<Problem> problems) {
        public boolean ok() {
            return !Problem.anyError(problems);
        }
    }

    private final PackRegistry registry;
    private final PackRequirementsCatalog catalog;
    private final PlatformInfo platform;

    public PackDependencyResolver(PackRegistry registry, PackRequirementsCatalog catalog, PlatformInfo platform) {
        this.registry = registry;
        this.catalog = catalog;
        this.platform = platform;
    }

    public PlatformInfo platform() {
        return platform;
    }

    public PackRegistry registry() {
        return registry;
    }

    public PackRequirements requirements(SangamPack pack) {
        return catalog.of(pack);
    }

    /**
     * @param requested    pack ids selected
     * @param ranges       optional range per pack id from the selection
     * @param installed    what the tenant already has (id to version)
     * @param allowUpgrade whether an installed version may be replaced
     */
    public Resolution resolve(Collection<String> requested, Map<String, String> ranges, Map<String, String> installed,
                              boolean allowUpgrade) {
        List<Problem> problems = new ArrayList<>();
        Map<String, List<String>> constraints = new LinkedHashMap<>();
        Map<String, SangamPack> closure = new LinkedHashMap<>();
        List<String> added = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        Set<String> wanted = new LinkedHashSet<>(requested);
        for (String id : wanted) {
            queue.add(id);
            String r = ranges == null ? null : ranges.get(id);
            if (r != null) {
                constraints.computeIfAbsent(id, k -> new ArrayList<>()).add(r + " (selection)");
            }
        }
        Set<String> missing = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (closure.containsKey(id) || missing.contains(id)) {
                continue;
            }
            SangamPack p = registry.find(id).orElse(null);
            if (p == null) {
                missing.add(id);
                problems.add(Problem.error(StepId.PACKS, "PACK_NOT_FOUND", id, "pack is not installed on this platform"
                        + " (neither shipped nor provided by an enabled plugin)"));
                continue;
            }
            closure.put(id, p);
            if (!wanted.contains(id)) {
                added.add(id);
            }
            catalog.of(p).packs().forEach((dep, range) -> {
                if (!range.isAny()) {
                    constraints.computeIfAbsent(dep, k -> new ArrayList<>()).add(range + " (" + id + ")");
                }
                queue.add(dep);
            });
        }
        Map<String, String> versions = new LinkedHashMap<>();
        Map<String, Set<String>> connectors = new LinkedHashMap<>();
        Map<String, Set<String>> keys = new LinkedHashMap<>();
        List<UpgradeStep> upgrades = new ArrayList<>();
        for (SangamPack p : closure.values()) {
            String id = p.descriptor().id();
            String version = p.descriptor().version();
            versions.put(id, version);
            PackRequirements q = catalog.of(p);
            if (SemVer.tryParse(version) == null) {
                problems.add(Problem.error(StepId.PACKS, "PACK_VERSION_INVALID", id, "version '" + version
                        + "' is not a semantic version"));
            }
            for (String c : constraints.getOrDefault(id, List.of())) {
                String range = c.substring(0, c.lastIndexOf(" ("));
                if (!VersionRange.parse(range).contains(version)) {
                    problems.add(Problem.error(StepId.PACKS, "PACK_VERSION_UNSATISFIED", id, "version " + version
                            + " does not satisfy " + c));
                }
            }
            if (!q.sangam().contains(platform.sangamVersion())) {
                problems.add(Problem.error(StepId.PACKS, "PLATFORM_INCOMPATIBLE", id, "needs platform " + q.sangam()
                        + ", this platform is " + platform.sangamVersion()));
            }
            if (!q.engine().contains(platform.engineVersion())) {
                problems.add(Problem.error(StepId.PACKS, "ENGINE_INCOMPATIBLE", id, "needs engine " + q.engine()
                        + ", this engine is " + platform.engineVersion()));
            }
            q.modules().forEach((m, r) -> {
                String have = platform.modules().get(m);
                if (have == null) {
                    problems.add(Problem.error(StepId.PACKS, "MODULE_MISSING", id, "needs module " + m + " " + r));
                } else if (!r.contains(have)) {
                    problems.add(Problem.error(StepId.PACKS, "MODULE_INCOMPATIBLE", id, "needs module " + m + " " + r
                            + ", found " + have));
                }
            });
            q.connectors().forEach(c -> connectors.computeIfAbsent(c, k -> new LinkedHashSet<>()).add(id));
            q.profileKeys().forEach(k -> keys.computeIfAbsent(k, x -> new LinkedHashSet<>()).add(id));
            conflicts(q, id, closure, installed, problems);
            upgrade(id, version, q, installed, allowUpgrade, upgrades, problems);
        }
        List<String> order = new ArrayList<>();
        if (missing.isEmpty()) {
            try {
                registry.deploymentOrder(wanted).forEach(p -> order.add(p.descriptor().id()));
                // dependencies declared only under requires.packs are not known to the registry's ordering
                closure.keySet().stream().filter(x -> !order.contains(x)).forEach(x -> order.add(0, x));
            } catch (IllegalStateException e) {
                problems.add(Problem.error(StepId.PACKS, "PACK_DEPENDENCY_CYCLE", String.join(",", wanted), e.getMessage()));
            }
        }
        return new Resolution(order, versions, added, connectors, keys, upgrades, problems);
    }

    private void conflicts(PackRequirements q, String id, Map<String, SangamPack> closure,
                           Map<String, String> installed, List<Problem> problems) {
        q.conflicts().forEach((other, range) -> {
            String version = closure.containsKey(other) ? closure.get(other).descriptor().version()
                    : installed == null ? null : installed.get(other);
            if (version != null && range.contains(version)) {
                problems.add(Problem.error(StepId.PACKS, "PACK_CONFLICT", id, "conflicts with " + other + "@" + version
                        + " (" + range + ")"));
            }
        });
    }

    private void upgrade(String id, String version, PackRequirements q, Map<String, String> installed,
                         boolean allowUpgrade, List<UpgradeStep> upgrades, List<Problem> problems) {
        String have = installed == null ? null : installed.get(id);
        if (have == null || have.equals(version)) {
            return;
        }
        SemVer from = SemVer.tryParse(have);
        SemVer to = SemVer.tryParse(version);
        if (from == null || to == null) {
            problems.add(Problem.error(StepId.PACKS, "UPGRADE_PATH_MISSING", id, "cannot compare " + have + " and " + version));
            return;
        }
        if (to.compareTo(from) < 0) {
            problems.add(Problem.error(StepId.PACKS, "DOWNGRADE_REFUSED", id, "installed " + have + " is newer than " + version));
            return;
        }
        if (!allowUpgrade) {
            problems.add(Problem.error(StepId.PACKS, "UPGRADE_NOT_ALLOWED", id, "installed " + have + ", available "
                    + version + ": set packs.allowUpgrade to upgrade"));
            return;
        }
        PackRequirements.UpgradePath path = q.upgrades().stream().filter(u -> u.from().contains(from)).findFirst().orElse(null);
        boolean major = to.major() != from.major();
        if (path == null && (major || !q.upgrades().isEmpty())) {
            problems.add(Problem.error(StepId.PACKS, "UPGRADE_PATH_MISSING", id, "no declared upgrade path from " + have
                    + " to " + version));
            return;
        }
        upgrades.add(new UpgradeStep(id, have, version,
                path == null ? "running cases stay on " + have + " (R43); migrate with PackMigrator if wanted" : path.migration(),
                major || (path != null && path.breaking())));
    }
}
