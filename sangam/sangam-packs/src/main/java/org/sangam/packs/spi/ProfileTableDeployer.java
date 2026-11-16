// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.spi;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.DecisionDefinition;
import org.operaton.bpm.engine.repository.Deployment;
import org.operaton.bpm.model.dmn.Dmn;
import org.operaton.bpm.model.dmn.DmnModelInstance;
import org.operaton.bpm.model.dmn.instance.Decision;

/**
 * DEC-225-4 (decision 225, IFC-189): deploys the delegation tables of a tenant's profile as the tenant's own decision
 * tables. The profile lists them under {@code delegation.tables} (profilegen {@code skeletons.py}): each entry has
 * {@code key}, {@code file} (relative to the profile root, {@code in/dmn/AN/ifms-procurement-mode.dmn}) and
 * {@code deployAs}. A {@code TENANT_OVERRIDE} table is deployed into the tenant separately from every pack (source
 * {@link #SOURCE}), so {@code Decisions.evaluate} picks it before the pack's copy of the central table: a State tenant
 * evaluates its own table.
 *
 * <p>Two steps, so a caller can refuse before it deploys anything else:
 * <ol>
 *   <li>{@link #plan}: every table is checked first. An unknown {@code deployAs}, a missing key or file, a path that
 *       leaves the profile root, a file that is not there, that declares a DTD or entity, or that holds no decision
 *       with the table's key refuses the whole set ({@link ProfileTableException}), so a tenant never runs on part of
 *       its tables.</li>
 *   <li>{@link #apply}: each table is one deployment {@code profile-table:<key>} with duplicate filtering, so the same
 *       content deploys nothing and a changed table becomes a new version. A table this deployer deployed earlier whose
 *       key the profile no longer lists is removed (its deployments, without cascade: the history stays), so a
 *       withdrawn override stops applying and the central table applies again.</li>
 * </ol>
 */
public final class ProfileTableDeployer {

    /** Deployment source of a profile table: not {@link PackDeployer#SOURCE}, so it counts as the tenant's own. */
    public static final String SOURCE = "sangam-profile-tables";

    /** Deployment name prefix; the key follows. */
    public static final String NAME_PREFIX = "profile-table:";

    /** The one supported {@code deployAs}. */
    public static final String TENANT_OVERRIDE = "TENANT_OVERRIDE";

    /** Refused profile tables; nothing was deployed. */
    public static final class ProfileTableException extends RuntimeException {
        private final String code;

        public ProfileTableException(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** The checked tables of one tenant, ready for {@link #apply}. */
    public static final class Plan {
        private final String tenantId;
        private final List<Table> tables;

        private Plan(String tenantId, List<Table> tables) {
            this.tenantId = tenantId;
            this.tables = List.copyOf(tables);
        }

        public String tenantId() {
            return tenantId;
        }

        public List<String> keys() {
            return tables.stream().map(Table::key).toList();
        }
    }

    private record Table(String key, String file, byte[] dmn) {
    }

    /** One deployed (or unchanged) table. */
    public record Result(String tenantId, String key, String file, String deploymentId, int version) {
    }

    /** What {@link #apply} did: the tables in force, and the withdrawn keys whose deployments it removed. */
    public record Sync(String tenantId, List<Result> tables, List<String> removed) {
    }

    private final RepositoryService repo;
    private final Function<String, byte[]> resources;

    /**
     * @param resources reads a table file by its profile-relative path; null when there is no such file
     */
    public ProfileTableDeployer(ProcessEngine engine, Function<String, byte[]> resources) {
        this.repo = Objects.requireNonNull(engine, "engine").getRepositoryService();
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    /** A reader of profile-relative paths under {@code root} on the class path of {@code loader}. */
    public static Function<String, byte[]> classpath(ClassLoader loader, String root) {
        String base = root.endsWith("/") ? root : root + "/";
        return path -> {
            try (InputStream in = loader.getResourceAsStream(base + path)) {
                return in == null ? null : in.readAllBytes();
            } catch (IOException e) {
                throw new ProfileTableException("PROFILE_TABLE_UNREADABLE", base + path + ": " + e.getMessage());
            }
        };
    }

    /** Checks {@code tables} (a profile's {@code delegation.tables}; null or empty: none) for {@code tenantId}. */
    public Plan plan(String tenantId, List<Map<String, Object>> tables) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ProfileTableException("TENANT_REQUIRED", "profile tables are deployed into a tenant");
        }
        List<Table> checked = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, Object> t : tables == null ? List.<Map<String, Object>>of() : tables) {
            String key = text(t.get("key"));
            String file = text(t.get("file"));
            String deployAs = text(t.get("deployAs"));
            if (key == null || file == null) {
                throw new ProfileTableException("PROFILE_TABLE_INVALID", "a delegation table needs key and file: " + t);
            }
            if (!TENANT_OVERRIDE.equals(deployAs)) {
                throw new ProfileTableException("PROFILE_TABLE_DEPLOY_AS_UNKNOWN",
                        "table " + key + ": deployAs " + deployAs + " is not " + TENANT_OVERRIDE);
            }
            if (!keys.add(key)) {
                throw new ProfileTableException("PROFILE_TABLE_DUPLICATE", "table " + key + " is listed twice");
            }
            if (file.startsWith("/") || file.contains("\\") || file.contains(":")
                    || List.of(file.split("/")).contains("..")) {
                throw new ProfileTableException("PROFILE_TABLE_INVALID", "table " + key + ": file " + file
                        + " is not a path inside the profile root");
            }
            byte[] dmn = resources.apply(file);
            if (dmn == null) {
                throw new ProfileTableException("PROFILE_TABLE_MISSING", "table " + key + ": " + file + " is not there");
            }
            if (!declares(dmn, key, file)) {
                throw new ProfileTableException("PROFILE_TABLE_KEY_MISMATCH", "table " + key + ": " + file
                        + " holds no decision with id " + key);
            }
            checked.add(new Table(key, file, dmn));
        }
        return new Plan(tenantId, checked);
    }

    /** Deploys a checked plan and removes the tenant's withdrawn profile tables. */
    public Sync apply(Plan plan) {
        String tenantId = plan.tenantId();
        List<Result> out = new ArrayList<>();
        for (Table t : plan.tables) {
            Deployment d = repo.createDeployment().tenantId(tenantId).name(NAME_PREFIX + t.key()).source(SOURCE)
                    .enableDuplicateFiltering(false).addInputStream(t.key() + ".dmn", new ByteArrayInputStream(t.dmn()))
                    .deploy();
            // an unchanged table: duplicate filtering returns the earlier deployment, which holds the definition
            DecisionDefinition def = repo.createDecisionDefinitionQuery().decisionDefinitionKey(t.key())
                    .tenantIdIn(tenantId).deploymentId(d.getId()).singleResult();
            if (def == null) {
                throw new ProfileTableException("PROFILE_TABLE_NOT_DEPLOYED", "table " + t.key() + ": deployment "
                        + d.getId() + " holds no decision " + t.key() + " for tenant " + tenantId);
            }
            out.add(new Result(tenantId, t.key(), t.file(), d.getId(), def.getVersion()));
        }
        Set<String> keep = new LinkedHashSet<>(plan.keys());
        TreeSet<String> removed = new TreeSet<>();
        for (Deployment d : repo.createDeploymentQuery().tenantIdIn(tenantId).list()) {
            String name = d.getName();
            if (SOURCE.equals(d.getSource()) && name != null && name.startsWith(NAME_PREFIX)
                    && !keep.contains(name.substring(NAME_PREFIX.length()))) {
                repo.deleteDeployment(d.getId(), false);
                removed.add(name.substring(NAME_PREFIX.length()));
            }
        }
        return new Sync(tenantId, List.copyOf(out), List.copyOf(removed));
    }

    /** {@link #plan} then {@link #apply}. */
    public Sync deploy(String tenantId, List<Map<String, Object>> tables) {
        return apply(plan(tenantId, tables));
    }

    /** The tenants with at least one pack or profile-table deployment: the tenants a profile change re-syncs. */
    public Collection<String> deployedTenants() {
        TreeSet<String> out = new TreeSet<>();
        for (Deployment d : repo.createDeploymentQuery().list()) {
            if (d.getTenantId() != null && (PackDeployer.SOURCE.equals(d.getSource()) || SOURCE.equals(d.getSource()))) {
                out.add(d.getTenantId());
            }
        }
        return out;
    }

    private static boolean declares(byte[] dmn, String key, String file) {
        String text = new String(dmn, StandardCharsets.UTF_8);
        if (text.contains("<!DOCTYPE") || text.contains("<!ENTITY")) {
            throw new ProfileTableException("PROFILE_TABLE_INVALID", "table " + key + ": " + file
                    + " declares a DTD or an entity");
        }
        DmnModelInstance model;
        try {
            model = Dmn.readModelFromStream(new ByteArrayInputStream(dmn));
        } catch (RuntimeException e) {
            throw new ProfileTableException("PROFILE_TABLE_INVALID", "table " + key + ": " + file
                    + " is not a DMN model: " + e.getMessage());
        }
        return model.getModelElementsByType(Decision.class).stream().anyMatch(d -> key.equals(d.getId()));
    }

    private static String text(Object o) {
        return o == null || o.toString().isBlank() ? null : o.toString().trim();
    }
}
