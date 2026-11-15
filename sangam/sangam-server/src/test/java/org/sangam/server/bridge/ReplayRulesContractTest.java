// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.sangam.rest.adapter.memory.GroupPermissionAdapter;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Replay fails closed (coordinator follow-up to decision 258): every {@code x-sangam-command-type} of the source contract
 * {@code sangam-rest/api/sangam-api.yaml} has an explicit replay rule in {@link SecurityPermissionBridge} naming the
 * permission its route requires ({@code x-sangam-permission}); the routes whose contract text is not one permission name
 * are listed here with the rule each must get, and the contract text they were checked against. An unknown type is
 * refused. The contract is read from the source tree, not through {@link ReplayRules}, so the class path copy, the
 * parser and the explicit entries are all checked. Written for the test phase (not run here).
 */
class ReplayRulesContractTest {

    private static final Path CONTRACT = Path.of("..", "sangam-rest", "api", "sangam-api.yaml");
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*(\\.[a-z0-9-]+)+");

    private record Expected(String contractText, ReplayRules.Rule rule) {
    }

    private static final ReplayRules.Rule CELL = new ReplayRules.InGroup(Set.of("CELL_ADMIN", "sangam-cell-admin"));
    private static final ReplayRules.Rule OWN = new ReplayRules.Authenticated();
    private static final ReplayRules.Rule BULK = new ReplayRules.BulkJobSink();
    private static final String BULK_TEXT =
            "the permission declared by the object type sink (ops.variable: ops.tenant.admin)";

    /** The routes whose contract text is not one permission name, with the rule the route itself enforces. */
    private static final Map<String, Expected> NOT_A_NAME = Map.ofEntries(
            Map.entry("me.notification.read", new Expected(null, OWN)),
            Map.entry("compliance.consent.give", new Expected("authenticated (own) / compliance.modify", OWN)),
            Map.entry("compliance.consent.withdraw", new Expected("owner / compliance.modify", OWN)),
            Map.entry("compliance.consent-artefact.ingest", new Expected(null, OWN)),
            Map.entry("compliance.rights-request.submit", new Expected(null, OWN)),
            Map.entry("compliance.rights.otp.issue", new Expected(null, OWN)),
            Map.entry("cell.tenant.import.prepare", new Expected("role CELL_ADMIN", CELL)),
            Map.entry("cell.tenant.freeze", new Expected("role CELL_ADMIN", CELL)),
            Map.entry("cell.tenant.export", new Expected("role CELL_ADMIN", CELL)),
            Map.entry("cell.tenant.import", new Expected("role CELL_ADMIN", CELL)),
            Map.entry("cell.tenant.activate", new Expected("role CELL_ADMIN", CELL)),
            Map.entry("cell.tenant.moved", new Expected("role CELL_ADMIN", CELL)),
            Map.entry("cell.tenant.unfreeze", new Expected("role CELL_ADMIN", CELL)),
            Map.entry("cell.tenant.import.drop", new Expected("role CELL_ADMIN", CELL)),
            Map.entry("cell.tenant.purge", new Expected("role CELL_ADMIN", CELL)),
            Map.entry("cell.parked-inbound.replay", new Expected("role CELL_ADMIN", CELL)),
            Map.entry("bulk.job.create", new Expected(BULK_TEXT, BULK)),
            Map.entry("bulk.chunk.upload", new Expected(BULK_TEXT, BULK)),
            Map.entry("bulk.job.close", new Expected(BULK_TEXT, BULK)),
            Map.entry("bulk.job.abort", new Expected(BULK_TEXT, BULK)));

    /** Owner decision 273: the permission is the stored bulk job's sink permission (BulkReplayPermissionTest). */
    private static final Set<String> BULK_TYPES = Set.of("bulk.job.create", "bulk.chunk.upload", "bulk.job.close",
            "bulk.job.abort");

    /** No bulk-job resolver here: the bulk types have their rule but are refused on replay. */
    private final SecurityPermissionBridge bridge = new SecurityPermissionBridge(null, null, new GroupPermissionAdapter(),
            new ReplayRules(contract()), null);

    @SuppressWarnings("unchecked")
    private static Map<String, Object> contract() {
        try (InputStream in = Files.newInputStream(CONTRACT)) {
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(64 * 1024 * 1024);
            return (Map<String, Object>) new Yaml(new SafeConstructor(options)).load(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read " + CONTRACT.toAbsolutePath(), e);
        }
    }

    /** Command type → the x-sangam-permission of every operation of the type (null when none is declared). */
    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> declared() {
        Map<String, Set<String>> out = new TreeMap<>();
        Map<String, Object> paths = (Map<String, Object>) contract().get("paths");
        for (Object item : paths.values()) {
            for (Map.Entry<String, Object> op : ((Map<String, Object>) item).entrySet()) {
                if (!(op.getValue() instanceof Map<?, ?> o) || o.get("x-sangam-command-type") == null) {
                    continue;
                }
                Object p = o.get("x-sangam-permission");
                out.computeIfAbsent(o.get("x-sangam-command-type").toString(), k -> new LinkedHashSet<>())
                        .add(p == null ? null : p.toString());
            }
        }
        return out;
    }

    @Test
    void everyContractCommandTypeHasTheRuleOfItsRoute() {
        Map<String, Set<String>> declared = declared();
        assertTrue(declared.size() > 500, "the contract declares the command types (" + declared.size() + ")");
        List<String> wrong = new ArrayList<>();
        int mapped = 0;
        for (Map.Entry<String, Set<String>> e : declared.entrySet()) {
            String type = e.getKey();
            Set<String> texts = e.getValue();
            ReplayRules.Rule actual = bridge.replayRule(type);
            ReplayRules.Rule expected;
            if (NOT_A_NAME.containsKey(type)) {
                Expected x = NOT_A_NAME.get(type);
                if (!(texts.size() == 1 && texts.contains(x.contractText()))) {
                    wrong.add(type + ": contract text " + texts + " is no longer " + x.contractText() + " (review the rule)");
                    continue;
                }
                expected = x.rule();
            } else {
                if (texts.stream().anyMatch(t -> t == null || !NAME.matcher(t).matches())) {
                    wrong.add(type + ": contract text " + texts + " is not a permission name and has no explicit rule");
                    continue;
                }
                // routes of one type that declare different permissions: every one is required
                expected = new ReplayRules.Holds(texts);
            }
            if (!java.util.Objects.equals(expected, actual)) {
                wrong.add(type + ": expected " + expected + ", bridge has " + actual);
            } else if (actual != null) {
                mapped++;
            }
        }
        assertEquals(List.of(), wrong);
        assertEquals(declared.size(), mapped, "every contract command type is mapped");
    }

    @Test
    void theExplicitTablesAreExactlyThoseOfTheContract() {
        Map<String, Set<String>> declared = declared();
        assertTrue(declared.keySet().containsAll(NOT_A_NAME.keySet()), "an explicit entry for a type no longer in the contract");
        assertEquals(NOT_A_NAME.keySet(), ReplayRules.EXPLICIT.keySet());
        Map<String, ReplayRules.Rule> rules = new HashMap<>();
        ReplayRules.EXPLICIT.forEach((k, v) -> rules.put(k, v.rule()));
        NOT_A_NAME.forEach((k, v) -> assertEquals(v.rule(), rules.get(k), k));
    }

    /** W4-M contract round (sangam-api.yaml B3AF3A42...): the routes whose rule now comes from their declaration. */
    @Test
    void contractRoundOfWaveFourMIsPickedUp() {
        assertEquals(ReplayRules.Holds.of("task.write"), bridge.replayRule("me.leave.request"));
        assertEquals(ReplayRules.Holds.of("admin.write"), bridge.replayRule("power-table.submit"));
        assertEquals(ReplayRules.Holds.of("power-table.approve"), bridge.replayRule("power-table.decide"));
        assertNull(bridge.replayRule("power-table.transition"), "the old combined type is gone from the contract");
        // decision 265: the 202 record create on a REST-backed data object replays on the route permission; the
        // operation GET is a read (not an idempotent command) and has no command type. Decision 284: that permission
        // is lowcode.record.write (it was lowcode.read), so a reader alone no longer gets a stored create back
        assertEquals(ReplayRules.Holds.of("lowcode.record.write"), bridge.replayRule("data-object.record.create"));
        SecurityPermissionBridge byGroups = new SecurityPermissionBridge(null, null, new GroupPermissionAdapter(Map.of(
                "readers", Set.of("lowcode.read"), "writers", Set.of("lowcode.read", "lowcode.record.write"))),
                new ReplayRules(contract()), null);
        RequestContext reader = new RequestContext("t1", "p1", Set.of("readers"), null, null, null, null, null, "OIDC");
        RequestContext writer = new RequestContext("t1", "p1", Set.of("writers"), null, null, null, null, null, "OIDC");
        assertFalse(byGroups.mayReplay(reader, "data-object.record.create", "op-1"), "lowcode.read alone no longer suffices");
        assertTrue(byGroups.mayReplay(writer, "data-object.record.create", "op-1"));
        assertFalse(ReplayRules.EXPLICIT.containsKey("me.leave.request"));
    }

    @Test
    void theRulesOnTheClassPathAreThoseOfTheSourceContract() {
        assertEquals(new ReplayRules(contract()).size(), ReplayRules.fromClassPath().size());
    }

    @Test
    void anUnknownTypeIsRefusedEvenForThePlatformAdministrator() {
        RequestContext admin = new RequestContext("t1", "p1", Set.of(Permissions.GROUP_PLATFORM, Permissions.GROUP_ADMIN),
                null, null, null, null, null, "OIDC");
        for (String type : new String[] {"process.not-in-the-contract", "history.other", "anything", "", null}) {
            assertNull(bridge.replayRule(type), String.valueOf(type));
            assertFalse(bridge.mayReplay(admin, type, null), String.valueOf(type));
        }
        // without a resolver (and without a bulk-job ref) a bulk replay is refused (decision 273)
        for (String type : BULK_TYPES) {
            assertFalse(bridge.mayReplay(admin, type, null), type);
        }
    }

    @Test
    void aMappedTypeStillReplaysForAHolderAndNotForAFormerOne() {
        RequestContext user = new RequestContext("t1", "p1", Set.of(Permissions.GROUP_USER), null, null, null, null, null,
                "OIDC");
        RequestContext auditor = new RequestContext("t1", "p1", Set.of(Permissions.GROUP_AUDITOR), null, null, null, null,
                null, "OIDC");
        // task.claim requires task.write (no stored resource: the endpoint rule alone decides)
        assertTrue(bridge.mayReplay(user, "task.claim", null));
        assertFalse(bridge.mayReplay(auditor, "task.claim", null), "process.read or any permission no longer suffices");
        // the cell role is a group, not a permission
        RequestContext cell = new RequestContext("t1", "svc", Set.of("CELL_ADMIN"), null, null, null, null, null, "OIDC");
        assertTrue(bridge.mayReplay(cell, "cell.tenant.freeze", null));
        assertFalse(bridge.mayReplay(user, "cell.tenant.freeze", null));
        // self-service: the principal-bound caller of the tenant
        assertTrue(bridge.mayReplay(auditor, "me.notification.read", null));
        assertFalse(bridge.mayReplay(new RequestContext(null, "p1", Set.of(), null, null, null, null, null, "OIDC"),
                "me.notification.read", null));
    }
}
