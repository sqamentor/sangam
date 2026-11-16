// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.sangam.config.admin.ConfigAdminService;
import org.sangam.config.common.Tree;
import org.sangam.config.locale.ProfileBusinessClock;
import org.sangam.config.profile.LayerModel;
import org.sangam.config.profile.ProfileDocument;
import org.sangam.config.profile.ProfileLoader;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.config.store.ConfigSchema;
import org.sangam.config.store.JdbcConfigStore;
import org.sangam.core.layer.Dialect;
import org.sangam.core.time.BusinessClock;
import org.sangam.packs.core.Decisions;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.ProfileTableDeployer;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.server.config.ConfigWiring;
import org.sangam.server.config.PacksWiring;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * DEC-225-4 end to end on H2, through the server's own wiring ({@link PacksWiring#sangamPacksTenantDeployer}): the
 * shipped profiles imported into {@link JdbcConfigStore}; tenant {@code AN} (Andaman &amp; Nicobar, whose profile lists
 * its own {@code ifms-financial-powers} and {@code ifms-procurement-mode}) and a tenant without a profile both get the
 * {@code gov.tender} pack. AN then evaluates its own procurement table, the other tenant the pack's copy of the GOI
 * table. A profile change (a new AN version, maker-checker, activation) re-deploys AN's tables; a version whose table
 * file is missing is refused and audited, and a deploy of AN then fails before any pack is deployed.
 */
@SuppressWarnings("unchecked")
class ProfileTablesDeploymentTest {

    static final String STATE = "AN";
    static final String OTHER = "t-no-profile";
    static final String KEY = "ifms-procurement-mode";
    static final String URL = "jdbc:h2:mem:dec225-4-tables;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    static ProcessEngine engine;
    static JdbcConfigStore store;
    static ConfigAdminService admin;
    static ProfileDocument an;
    static PacksTenantDeployer deployer;
    static final List<String> audit = new CopyOnWriteArrayList<>();
    static final List<Map<String, ?>> payloads = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void setUp() throws Exception {
        PackRuntime.reset();
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(URL);
        ds.setUser("sa");
        ds.setPassword("");
        org.sangam.engine.db.DatabaseGuard.prepareH2(ds);
        try (Connection c = ds.getConnection()) {
            ConfigSchema.create(c, Dialect.POSTGRESQL);
        }
        store = new JdbcConfigStore(ds, Dialect.POSTGRESQL);
        ClassLoader cl = ProfileTablesDeploymentTest.class.getClassLoader();
        List<ProfileDocument> docs = ProfileLoader.loadIndex(cl, "profiles/profiles.idx");
        an = docs.stream().filter(d -> d.id().equals(STATE)).findFirst().orElseThrow();
        LayerModel layers = LayerModel.from(ProfileLoader.parseTree(ProfileLoader.readResource(cl, "profiles/layers.yaml"))
                .get("layers"));
        ProfileResolver resolver = new ProfileResolver(store, layers);
        admin = new ConfigAdminService(store, resolver, new ConfigWiring().sangamProfileValidator(layers), store,
                Clock.systemUTC());
        admin.importActive(docs, "release");

        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl(URL);
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        engine = cfg.buildProcessEngine();

        PackRegistry packs = PackRegistry.discover();
        AuditPort port = (AuditPort) Proxy.newProxyInstance(cl, new Class<?>[] {AuditPort.class}, (p, m, args) -> {
            if (m.getName().equals("record")) {
                audit.add(args[2] + " " + args[1]);
                payloads.add((Map<String, ?>) args[3]);
                return "a" + audit.size();
            }
            throw new UnsupportedOperationException(m.getName());
        });
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("resolver", resolver);
        beans.addBean("clock", new ProfileBusinessClock(resolver, Clock.systemUTC()));
        beans.addBean("admin", admin);
        deployer = new PacksWiring().sangamPacksTenantDeployer(new PackDeployer(engine, packs), packs, port,
                engine, beans.getBeanProvider(ProfileResolver.class), beans.getBeanProvider(BusinessClock.class),
                beans.getBeanProvider(ConfigAdminService.class));
        deployer.select(STATE, java.util.Set.of("gov.tender"));
        deployer.select(OTHER, java.util.Set.of("gov.tender"));
    }

    @AfterAll
    static void tearDown() {
        PackRuntime.reset();
        if (engine != null) {
            engine.close();
        }
    }

    static String ruleRef(String tenant) {
        Map<String, Object> v = new HashMap<>();
        v.put("amount", 1_000_000L);
        v.put("scientificDepartment", false);
        Map<String, Object> row = Decisions.evaluate(engine, tenant, KEY, v).single();
        assertEquals("DIRECT", row.get("procurementMode"));
        return String.valueOf(row.get("ruleRef"));
    }

    static long deployments() {
        return engine.getRepositoryService().createDeploymentQuery().count();
    }

    /** A new AN version: the shipped document with {@code delegation.tables} replaced (null: no delegation section). */
    static ProfileDocument anWith(int n, List<Map<String, Object>> tables) {
        Map<String, Object> data = Tree.copyMap(an.data());
        if (tables == null) {
            data.remove("delegation");
        } else {
            Map<String, Object> delegation = Tree.copyMap(Tree.map(data.get("delegation")));
            delegation.put("tables", tables);
            data.put("delegation", delegation);
        }
        String version = an.version().substring(0, an.version().lastIndexOf('.') + 1)
                + (Integer.parseInt(an.version().substring(an.version().lastIndexOf('.') + 1)) + n);
        return new ProfileDocument(an.id(), an.layer(), version, an.effectiveFrom(), an.effectiveTo(), an.extendsIds(),
                an.adopt(), an.name(), an.source(), an.placeholders(), an.locked(), data);
    }

    static int latestVersion(String key) {
        return engine.getRepositoryService().createDecisionDefinitionQuery().decisionDefinitionKey(key)
                .tenantIdIn(STATE).latestVersion().singleResult().getVersion();
    }

    static void activate(ProfileDocument d) {
        admin.saveDraft(d, "an.maker");
        admin.submit(d.id(), d.version(), "an.maker");
        admin.approve(d.id(), d.version(), "an.checker", store.get(d.id(), d.version()).orElseThrow().hash());
        admin.activate(d.id(), d.version(), "an.admin", List.of());
    }

    @Test
    void theStateRunsOnItsOwnTablesAndAProfileChangeRedeploysThem() {
        Map<String, String> out = deployer.deploy(STATE);
        // a key's version counts every deployment of it in the tenant: the gov.tender pack's copy of KEY is version 1
        // in AN, so the profile's table is the tenant's latest version of each key, not a fixed number
        assertTrue(out.containsKey("profile-table:" + KEY + "@" + latestVersion(KEY)), out.toString());
        assertTrue(out.containsKey("profile-table:ifms-financial-powers@" + latestVersion("ifms-financial-powers")),
                out.toString());
        assertTrue(ruleRef(STATE).startsWith("[U] central default, replace from GFR 2017"), ruleRef(STATE));
        assertTrue(audit.contains("PROFILE_TABLES_DEPLOYED sec:" + STATE), audit.toString());

        Map<String, String> other = deployer.deploy(OTHER);
        assertTrue(other.keySet().stream().noneMatch(k -> k.startsWith("profile-table:")), other.toString());
        assertTrue(ruleRef(OTHER).startsWith("GFR 2017"), ruleRef(OTHER));

        // a new AN version without its own tables: the listener withdraws them and the central copy applies again
        activate(anWith(1, null));
        assertTrue(ruleRef(STATE).startsWith("GFR 2017"), ruleRef(STATE));
        Map<String, ?> last = payloads.get(payloads.size() - 1);
        assertEquals(List.of("ifms-financial-powers", KEY), last.get("removed"), last.toString());

        // the next version lists the procurement table again: deployed on activation
        Map<String, Object> procurement = new java.util.LinkedHashMap<>(Tree.maps(Tree.map(an.data().get("delegation"))
                .get("tables")).get(1));
        assertEquals(KEY, procurement.get("key"));
        activate(anWith(2, List.of(procurement)));
        assertTrue(ruleRef(STATE).startsWith("[U] central default"), ruleRef(STATE));

        // a version whose table file is missing: refused and audited, the tables in force stay
        Map<String, Object> missing = new java.util.LinkedHashMap<>(procurement);
        missing.put("file", "in/dmn/AN/no-such-table.dmn");
        int before = audit.size();
        activate(anWith(3, List.of(missing)));
        assertTrue(audit.subList(before, audit.size()).contains("PROFILE_TABLES_REFUSED sec:" + STATE), audit.toString());
        assertTrue(ruleRef(STATE).startsWith("[U] central default"), ruleRef(STATE));
        // and a deploy of the tenant is refused before any pack is deployed
        long count = deployments();
        ApiProblem p = assertThrows(ApiProblem.class, () -> deployer.deploy(STATE));
        assertEquals(422, p.status());
        assertEquals("PROFILE_TABLE_MISSING", p.code());
        assertEquals(count, deployments());
        assertTrue(new ArrayList<>(deployer.tenantsToSync()).containsAll(List.of(STATE, OTHER)));
        assertTrue(ProfileTableDeployer.SOURCE.equals(engine.getRepositoryService().createDeploymentQuery()
                .tenantIdIn(STATE).list().stream().filter(d -> d.getName().startsWith("profile-table:"))
                .findFirst().orElseThrow().getSource()));
    }
}
