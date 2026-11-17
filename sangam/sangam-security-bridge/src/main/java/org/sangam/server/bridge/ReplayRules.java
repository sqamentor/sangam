// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.sangam.rest.support.Permissions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * R41 step 4: what a caller must still satisfy to receive a stored command response, per command type. The table is
 * the OpenAPI contract itself ({@value Permissions#CONTRACT_RESOURCE} on the class path, as sangam-rest's {@code CommandCatalog}):
 * each {@code x-sangam-command-type} gets the permission its route declares in {@code x-sangam-permission} (every one,
 * when routes of one type declare different permissions). A route whose contract text is not one permission name has an
 * explicit entry in {@link #EXPLICIT}, valid only while the contract still says exactly that text. A type with no rule
 * (not in the contract, or a text with no explicit entry) is refused: replay fails closed.
 */
final class ReplayRules {

    /** A replay requirement. */
    sealed interface Rule permits Holds, InGroup, Authenticated, BulkJobSink {
    }

    /** Every listed endpoint permission is still held ({@code PermissionPort.has}). */
    record Holds(Set<String> permissions) implements Rule {
        Holds {
            permissions = Set.copyOf(permissions);
        }

        static Holds of(String... permissions) {
            return new Holds(Set.of(permissions));
        }
    }

    /** The caller is in one of the groups (a service role checked by group, not by permission). */
    record InGroup(Set<String> groups) implements Rule {
        InGroup {
            groups = Set.copyOf(groups);
        }
    }

    /**
     * The route asks for an authenticated caller of the tenant and nothing more (self-service or public submissions).
     * The stored response is bound to the principal that created it (IdempotencyStore, 422
     * {@code IDEMPOTENCY_KEY_PRINCIPAL_MISMATCH}), so only that principal reaches a replay.
     */
    record Authenticated() implements Rule {
    }

    /**
     * Owner decision 273: the permission of the stored bulk job's sink. The stored resource ref must be exactly
     * {@value #BULK_JOB_REF}{@code <id>}, a {@link BulkJobPermissions} resolver must be present and resolve the job in
     * the caller's tenant, and the caller must still hold that permission; anything else refuses the replay.
     */
    record BulkJobSink() implements Rule {
    }

    /** Resource ref prefix of a bulk job (BulkController, {@code X-Sangam-Resource-Ref}). */
    static final String BULK_JOB_REF = "bulk-job:";

    /** A contract text that is not one permission name, and the rule that stands for it. */
    record Explicit(String contractText, Rule rule) {
    }

    /** The {@code x-sangam-permission} of a plain route: one dotted permission name. */
    static final Pattern PERMISSION_NAME = Pattern.compile("[a-z][a-z0-9-]*(\\.[a-z0-9-]+)+");

    private static final Rule CELL_ADMIN = new InGroup(Set.of(Permissions.CELL_ADMIN_ROLE, "sangam-cell-admin"));
    private static final String CELL_TEXT = "role CELL_ADMIN";
    private static final String BULK_TEXT =
            "the permission declared by the object type sink (ops.variable: ops.tenant.admin)";

    /** Command type → the exact contract text it replaces (null: the route declares no permission) and its rule. */
    static final Map<String, Explicit> EXPLICIT = Map.ofEntries(
            // self-service routes of the caller's own records (WorkspaceController)
            Map.entry("me.notification.read", new Explicit(null, new Authenticated())),
            // a data principal's own consent; recording or withdrawing for another needs compliance.modify in the
            // controller, and a replay goes only to the principal that made the call
            Map.entry("compliance.consent.give",
                    new Explicit("authenticated (own) / compliance.modify", new Authenticated())),
            Map.entry("compliance.consent.withdraw", new Explicit("owner / compliance.modify", new Authenticated())),
            // public routes (SecurityConfig permitAll); an anonymous call never reaches the idempotency store
            Map.entry("compliance.consent-artefact.ingest", new Explicit(null, new Authenticated())),
            Map.entry("compliance.rights-request.submit", new Explicit(null, new Authenticated())),
            // owner decision 360: the one-time-code challenge of a rights request, public like the submission
            Map.entry("compliance.rights.otp.issue", new Explicit(null, new Authenticated())),
            // cell service role (CellTenantAdminController, CellParkedInboundController requireRole)
            Map.entry("cell.tenant.import.prepare", new Explicit(CELL_TEXT, CELL_ADMIN)),
            Map.entry("cell.tenant.freeze", new Explicit(CELL_TEXT, CELL_ADMIN)),
            Map.entry("cell.tenant.export", new Explicit(CELL_TEXT, CELL_ADMIN)),
            Map.entry("cell.tenant.import", new Explicit(CELL_TEXT, CELL_ADMIN)),
            Map.entry("cell.tenant.activate", new Explicit(CELL_TEXT, CELL_ADMIN)),
            Map.entry("cell.tenant.moved", new Explicit(CELL_TEXT, CELL_ADMIN)),
            Map.entry("cell.tenant.unfreeze", new Explicit(CELL_TEXT, CELL_ADMIN)),
            Map.entry("cell.tenant.import.drop", new Explicit(CELL_TEXT, CELL_ADMIN)),
            Map.entry("cell.tenant.purge", new Explicit(CELL_TEXT, CELL_ADMIN)),
            Map.entry("cell.parked-inbound.replay", new Explicit(CELL_TEXT, CELL_ADMIN)),
            // owner decision 273: the route permission is the stored job's sink permission (BulkController.requireType)
            Map.entry("bulk.job.create", new Explicit(BULK_TEXT, new BulkJobSink())),
            Map.entry("bulk.chunk.upload", new Explicit(BULK_TEXT, new BulkJobSink())),
            Map.entry("bulk.job.close", new Explicit(BULK_TEXT, new BulkJobSink())),
            Map.entry("bulk.job.abort", new Explicit(BULK_TEXT, new BulkJobSink())));

    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");

    private static volatile ReplayRules contract;

    private final Map<String, Rule> rules;

    /** Builds the rules from a parsed OpenAPI document (its {@code paths} object). */
    ReplayRules(Map<String, Object> openApi) {
        Map<String, Set<String>> texts = texts(openApi);
        Map<String, Rule> out = new HashMap<>();
        texts.forEach((type, declared) -> {
            Rule r = ruleOf(type, declared);
            if (r != null) {
                out.put(type, r);
            }
        });
        this.rules = Map.copyOf(out);
    }

    /** The rules of {@value Permissions#CONTRACT_RESOURCE} on the class path, read once. */
    static ReplayRules fromClassPath() {
        ReplayRules r = contract;
        if (r == null) {
            synchronized (ReplayRules.class) {
                r = contract;
                if (r == null) {
                    r = new ReplayRules(load());
                    contract = r;
                }
            }
        }
        return r;
    }

    Optional<Rule> rule(String commandType) {
        return commandType == null ? Optional.empty() : Optional.ofNullable(rules.get(commandType));
    }

    int size() {
        return rules.size();
    }

    private static Rule ruleOf(String type, Set<String> declared) {
        Explicit e = EXPLICIT.get(type);
        if (e != null) {
            // valid only while every route of the type still declares exactly the text the entry was written for
            return declared.size() == 1 && declared.contains(e.contractText()) ? e.rule() : null;
        }
        if (declared.isEmpty() || declared.stream().anyMatch(t -> t == null || !PERMISSION_NAME.matcher(t).matches())) {
            return null;
        }
        return new Holds(declared);
    }

    /** Command type → every {@code x-sangam-permission} its operations declare ({@code null} for none). */
    @SuppressWarnings("unchecked")
    static Map<String, Set<String>> texts(Map<String, Object> openApi) {
        Object paths = openApi == null ? null : openApi.get("paths");
        if (!(paths instanceof Map<?, ?> pathMap)) {
            throw new IllegalStateException(Permissions.CONTRACT_RESOURCE + " has no paths");
        }
        Map<String, Set<String>> out = new HashMap<>();
        for (Object item : pathMap.values()) {
            if (!(item instanceof Map<?, ?> ops)) {
                continue;
            }
            for (Map.Entry<?, ?> op : ops.entrySet()) {
                if (!METHODS.contains(String.valueOf(op.getKey())) || !(op.getValue() instanceof Map<?, ?> operation)) {
                    continue;
                }
                Map<String, Object> o = (Map<String, Object>) operation;
                Object type = o.get("x-sangam-command-type");
                if (type == null) {
                    continue;
                }
                Object perm = o.get("x-sangam-permission");
                out.computeIfAbsent(type.toString(), k -> new LinkedHashSet<>())
                        .add(perm == null ? null : Objects.toString(perm));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() {
        try (InputStream in = ReplayRules.class.getClassLoader().getResourceAsStream(Permissions.CONTRACT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(Permissions.CONTRACT_RESOURCE + " is not on the class path");
            }
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(64 * 1024 * 1024);
            return (Map<String, Object>) new Yaml(new SafeConstructor(options)).load(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + Permissions.CONTRACT_RESOURCE, e);
        }
    }
}
