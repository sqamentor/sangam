// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;

import org.sangam.packs.core.Decisions;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.ProfileTableDeployer;
import org.sangam.packs.spi.ProfileTableDeployer.ProfileTableException;

/**
 * DEC-225-4: a State tenant evaluates the delegation tables of its own profile, not the pack's copy of the central
 * (GOI) table, and a tenant without such tables keeps the pack's copy. The tables are the ones the shipped Andaman &
 * Nicobar profile lists ({@code profiles/in/states/AN.yaml}, {@code delegation.tables}); the central copy is the
 * {@code gov.tender} pack's {@code ifms-procurement-mode}. Refusals are checked to deploy nothing.
 */
class ProfileTableDeployerTest {

    private static final String STATE = "AN";
    private static final String CENTRAL = "t-central";
    private static final String KEY = "ifms-procurement-mode";
    static ProcessEngine engine;
    static ProfileTableDeployer tables;

    @BeforeAll
    static void setUp() {
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-profile-tables;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel), as production
        engine = cfg.buildProcessEngine();
        PackDeployer packs = new PackDeployer(engine, PackRegistry.discover());
        packs.deploy(List.of("gov.tender"), STATE);
        packs.deploy(List.of("gov.tender"), CENTRAL);
        tables = new ProfileTableDeployer(engine, ProfileTableDeployer.classpath(
                ProfileTableDeployerTest.class.getClassLoader(), "profiles/"));
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    private static String ruleRef(String tenant) {
        Map<String, Object> v = new HashMap<>();
        v.put("amount", 1_000_000L);           // Rs 10,000 in paise: direct purchase in both tables
        v.put("scientificDepartment", false);
        Map<String, Object> row = Decisions.evaluate(engine, tenant, KEY, v).single();
        assertEquals("DIRECT", row.get("procurementMode"));
        return String.valueOf(row.get("ruleRef"));
    }

    private static long deployments() {
        return engine.getRepositoryService().createDeploymentQuery().count();
    }

    /** {@code delegation.tables} of a shipped State profile, read from its YAML (key, file, deployAs per entry). */
    static List<Map<String, Object>> shippedTables(String state) throws IOException {
        String yaml;
        try (InputStream in = ProfileTableDeployerTest.class.getClassLoader()
                .getResourceAsStream("profiles/in/states/" + state + ".yaml")) {
            assertTrue(in != null, "profile " + state + " is shipped");
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        boolean inDelegation = false;
        boolean inTables = false;
        for (String line : yaml.split("\r?\n")) {
            if (!line.startsWith(" ") && !line.isBlank()) {
                inDelegation = line.equals("delegation:");
                inTables = false;
                continue;
            }
            if (inDelegation && line.startsWith("  ") && !line.startsWith("   ") && !line.startsWith("  - ")) {
                inTables = line.equals("  tables:");
                continue;
            }
            if (!inTables) {
                continue;
            }
            String t = line.trim();
            if (line.startsWith("  - key: ")) {
                out.add(new LinkedHashMap<>());
                t = t.substring(2);
            }
            for (String field : List.of("key", "file", "deployAs")) {
                if (t.startsWith(field + ": ") && line.indexOf(field) <= 4) {
                    out.get(out.size() - 1).put(field, t.substring(field.length() + 2).trim());
                }
            }
        }
        return out;
    }

    @Test
    void aStateTenantEvaluatesItsOwnTablesAndOtherTenantsKeepTheCentralCopy() throws IOException {
        List<Map<String, Object>> an = shippedTables(STATE);
        assertEquals(List.of("ifms-financial-powers", KEY), an.stream().map(m -> m.get("key")).toList(), an.toString());
        // without the profile's tables both tenants run on the pack's copy of the GOI table
        assertTrue(ruleRef(STATE).startsWith("GFR 2017"), ruleRef(STATE));
        assertTrue(ruleRef(CENTRAL).startsWith("GFR 2017"), ruleRef(CENTRAL));

        ProfileTableDeployer.Sync first = tables.deploy(STATE, an);
        assertEquals(List.of("ifms-financial-powers", KEY), first.tables().stream().map(ProfileTableDeployer.Result::key)
                .toList());
        assertEquals(List.of(), first.removed());
        // a key's version counts every deployment of it in the tenant (the pack may hold a copy too): the profile's
        // table is the tenant's latest version of its key
        first.tables().forEach(r -> assertEquals(engine.getRepositoryService().createDecisionDefinitionQuery()
                .decisionDefinitionKey(r.key()).tenantIdIn(STATE).latestVersion().singleResult().getVersion(),
                r.version(), r.toString()));
        // the State evaluates its own table, from its own deployment; the other tenant is untouched
        assertTrue(ruleRef(STATE).startsWith("[U] central default, replace from GFR 2017"), ruleRef(STATE));
        String own = Decisions.evaluate(engine, STATE, KEY, Map.of("amount", 1_000_000L, "scientificDepartment",
                false)).definitionId();
        String ownDeployment = engine.getRepositoryService().getDecisionDefinition(own).getDeploymentId();
        assertEquals(ProfileTableDeployer.SOURCE, engine.getRepositoryService().createDeploymentQuery()
                .deploymentId(ownDeployment).singleResult().getSource());
        assertTrue(ruleRef(CENTRAL).startsWith("GFR 2017"), ruleRef(CENTRAL));

        // the same tables again: nothing new is deployed
        long before = deployments();
        ProfileTableDeployer.Sync again = tables.deploy(STATE, an);
        assertEquals(before, deployments());
        assertEquals(first.tables(), again.tables());

        // a profile that no longer lists the table: its deployments go, the central copy applies again
        ProfileTableDeployer.Sync withdrawn = tables.deploy(STATE, an.subList(0, 1));
        assertEquals(List.of(KEY), withdrawn.removed());
        assertTrue(ruleRef(STATE).startsWith("GFR 2017"), ruleRef(STATE));
        tables.deploy(STATE, an);
        assertTrue(ruleRef(STATE).startsWith("[U] central default"), ruleRef(STATE));
        assertTrue(tables.deployedTenants().containsAll(List.of(STATE, CENTRAL)), tables.deployedTenants().toString());
    }

    private static Map<String, Object> table(String key, String file, String deployAs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("file", file);
        m.put("deployAs", deployAs);
        return m;
    }

    private static String refused(ProfileTableDeployer d, List<Map<String, Object>> list) {
        long before = deployments();
        String code = assertThrows(ProfileTableException.class, () -> d.deploy("t-refused", list)).code();
        assertEquals(before, deployments(), "a refused set deploys nothing");
        return code;
    }

    @Test
    void aSetWithOneBadTableIsRefusedWholeAndDeploysNothing() {
        Map<String, Object> good = table(KEY, "in/dmn/AN/ifms-procurement-mode.dmn", "TENANT_OVERRIDE");
        assertEquals("PROFILE_TABLE_MISSING", refused(tables, List.of(good,
                table("ifms-financial-powers", "in/dmn/ZZ/ifms-financial-powers.dmn", "TENANT_OVERRIDE"))));
        assertEquals("PROFILE_TABLE_KEY_MISMATCH", refused(tables, List.of(good,
                table("ifms-financial-powers", "in/dmn/AN/ifms-procurement-mode.dmn", "TENANT_OVERRIDE"))));
        assertEquals("PROFILE_TABLE_DEPLOY_AS_UNKNOWN", refused(tables, List.of(good,
                table("ifms-financial-powers", "in/dmn/AN/ifms-financial-powers.dmn", "SHARED"))));
        assertEquals("PROFILE_TABLE_DEPLOY_AS_UNKNOWN", refused(tables, List.of(table(KEY,
                "in/dmn/AN/ifms-procurement-mode.dmn", null))));
        assertEquals("PROFILE_TABLE_INVALID", refused(tables, List.of(good,
                table("x", "in/dmn/../../packs/tender/ifms-procurement-mode.dmn", "TENANT_OVERRIDE"))));
        assertEquals("PROFILE_TABLE_INVALID", refused(tables, List.of(table(KEY, null, "TENANT_OVERRIDE"))));
        assertEquals("PROFILE_TABLE_DUPLICATE", refused(tables, List.of(good, good)));
        // a file that declares a DTD is refused before it is parsed
        Function<String, byte[]> dtd = p -> ("<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x \"y\">]>"
                + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\"/>").getBytes(StandardCharsets.UTF_8);
        assertEquals("PROFILE_TABLE_INVALID", refused(new ProfileTableDeployer(engine, dtd), List.of(good)));
        assertThrows(ProfileTableException.class, () -> tables.deploy(" ", List.of(good)));
        // none listed: nothing to deploy, nothing refused
        assertEquals(List.of(), tables.deploy("t-none", null).tables());
    }
}
