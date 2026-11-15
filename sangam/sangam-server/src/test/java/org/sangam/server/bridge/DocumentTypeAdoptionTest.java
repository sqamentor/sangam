// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.config.admin.ConfigAdminService;
import org.sangam.config.api.ConfigConnector;
import org.sangam.config.catalogue.DocumentTypeCatalogue;
import org.sangam.config.common.ConfigException;
import org.sangam.config.common.Tree;
import org.sangam.config.locale.ProfileBusinessClock;
import org.sangam.config.master.ClassificationRegistry;
import org.sangam.config.profile.LayerModel;
import org.sangam.config.profile.ProfileDocument;
import org.sangam.config.profile.ProfileLoader;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.config.profile.ProfileValidator;
import org.sangam.config.store.ConfigSchema;
import org.sangam.config.store.JdbcConfigStore;
import org.sangam.core.layer.Dialect;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.packs.IntegrationConnectorGateway;
import org.sangam.integration.packs.PackConnectorBindings;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.server.config.ConfigWiring;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * SL-35 / SL-119 / SL-120, end to end on H2: a tenant profile change that adds a document type is adopted by the
 * deployed bill pack's catalogue and check tasks ({@code config.documentType}: {@code bl_type} documentType.get,
 * {@code bl_check} documentType.check) without redeploying the pack, by NEW cases only; a case already waiting at
 * {@code bl_type} keeps the profile version it bound at its start (GP-19 / R43).
 *
 * <p>The path is the production one: the shipped profiles ({@code profiles/profiles.idx}, profilegen output) imported
 * into {@link JdbcConfigStore} ({@code CFG_*} tables on the engine's H2 database); the maker-checker
 * {@link ConfigAdminService} with the server's {@link ProfileValidator}; {@link ConfigCaseBindingPlugin} storing each
 * case's binding ({@code profileVersion}, {@code CFG_CASE_BINDING}); the {@link ConfigConnector} built, registered and
 * bound by {@link ConfigWiring}; the packs' gateway {@link IntegrationConnectorGateway} (query path, root case id); the
 * generated {@code gov.ifms.bill} pack deployed once for tenant {@code RJ}. The profile change itself is the tenant
 * administrator's runtime action (a new RJ version with one more {@code documentTypes} row), so no shipped data changes.
 */
class DocumentTypeAdoptionTest {

    static final String TENANT = "RJ";
    static final String FORM = "RJ-SL35-9";
    static final String TYPE = "RJ_SL35_BILL";
    static final List<String> CATALOGUE_STEPS = List.of("bl_profile", "bl_type", "bl_cls", "bl_check");
    static final String URL = "jdbc:h2:mem:sl35-doctype;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    static ProcessEngine engine;
    static JdbcConfigStore store;
    static LayerModel layers;
    static ProfileResolver resolver;
    static ConfigAdminService admin;
    static ProfileDocument rj;

    @BeforeAll
    static void setUp() throws Exception {
        PackRuntime.reset();
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(URL);
        ds.setUser("sa");   // the first connection creates the database: the same user the engine configuration uses
        ds.setPassword("");
        // MODE=PostgreSQL (the product layer's ON CONFLICT) has no BLOB type, which Operaton's H2 scripts use: the alias
        // the server creates before any other table (SchemaBootstrap)
        org.sangam.engine.db.DatabaseGuard.prepareH2(ds);
        try (Connection c = ds.getConnection()) {
            ConfigSchema.create(c, Dialect.POSTGRESQL);
        }
        store = new JdbcConfigStore(ds, Dialect.POSTGRESQL);

        ClassLoader cl = DocumentTypeAdoptionTest.class.getClassLoader();
        List<ProfileDocument> docs = ProfileLoader.loadIndex(cl, "profiles/profiles.idx");
        rj = docs.stream().filter(d -> d.id().equals(TENANT)).findFirst().orElseThrow();
        layers = LayerModel.from(ProfileLoader.parseTree(ProfileLoader.readResource(cl, "profiles/layers.yaml"))
                .get("layers"));
        resolver = new ProfileResolver(store, layers);
        ConfigWiring wiring = new ConfigWiring();
        ProfileValidator validator = wiring.sangamProfileValidator(layers);
        admin = new ConfigAdminService(store, resolver, validator, store, Clock.systemUTC());
        admin.importActive(docs, "release");
        ProfileBusinessClock businessClock = new ProfileBusinessClock(resolver, Clock.systemUTC());
        ConfigConnector connector = wiring.sangamConfigConnector(resolver, new ClassificationRegistry(store), store,
                businessClock);

        PackRegistry packs = PackRegistry.discover();
        ConnectorRegistry registry = new ConnectorRegistry();
        PackConnectorBindings bindings = PackConnectorBindings.fromPackData(packs.all());
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("registry", registry);
        beans.addBean("bindings", bindings);
        beans.addBean("resolver", resolver);
        wiring.sangamConfigConnectorRegistration(connector, beans.getBeanProvider(ConnectorRegistry.class),
                beans.getBeanProvider(PackConnectorBindings.class)).afterPropertiesSet();
        assertEquals("config", bindings.binding(TENANT, "config.documentType").orElseThrow().connectorId());

        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl(URL);
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        // the case binding at start (profileVersion + CFG_CASE_BINDING), as ConfigWiring.sangamConfigPlugin installs it
        cfg.getProcessEnginePlugins().add(new ConfigCaseBindingPlugin(beans.getBeanProvider(ProfileResolver.class),
                Dialect.POSTGRESQL, businessClock));
        engine = cfg.buildProcessEngine();
        // no simulator fallback: every catalogue step must be answered by the bound config connector
        PackRuntime.install(PackRuntime.ports().withGateway(new IntegrationConnectorGateway(null, engine, null, bindings,
                registry, null, null, null, null, null)));

        new PackDeployer(engine, packs).deploy(List.of("gov.ifms.bill"), TENANT);
    }

    @AfterAll
    static void tearDown() {
        PackRuntime.reset();
        if (engine != null) {
            engine.close();
        }
    }

    /** A new RJ version: the shipped RJ document plus one document type row (the tenant administrator's change). */
    static ProfileDocument rjWithNewType(String version, String chain) {
        Map<String, Object> data = Tree.copyMap(rj.data());
        List<Map<String, Object>> types = new ArrayList<>(Tree.maps(data.get("documentTypes")));
        assertTrue(types.stream().noneMatch(t -> FORM.equals(t.get("formNumber"))), "the form is new to RJ");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", TYPE);
        row.put("formNumber", FORM);
        row.put("family", "CONTINGENT");
        row.put("approvalChain", chain);
        row.put("sanction", "REFERENCE");
        row.put("integrations", Map.of());
        types.add(row);
        data.put("documentTypes", types);
        return new ProfileDocument(rj.id(), rj.layer(), version, rj.effectiveFrom(), rj.effectiveTo(), rj.extendsIds(),
                rj.adopt(), rj.name(), rj.source(), rj.placeholders(), rj.locked(), data);
    }

    static void makerChecker(ProfileDocument d) {
        admin.saveDraft(d, "rj.maker");
        admin.submit(d.id(), d.version(), "rj.maker");
        admin.approve(d.id(), d.version(), "rj.checker", store.get(d.id(), d.version()).orElseThrow().hash());
    }

    static String startBill(String bk) {
        return engine.getRuntimeService().createProcessInstanceByKey("sangam-ifms-bill").businessKey(bk)
                .processDefinitionTenantId(TENANT).setVariables(Map.of("billType", FORM,
                        "headOfAccount", "05-2054-00-001-01-00-13", "amount", 100_000L, "sanctionRef", "SAN/2026/35"))
                .execute().getId();
    }

    /** Runs the given catalogue steps of a case (each an asynchronous job) the way the job executor would. */
    static void run(String pid, List<String> steps) {
        for (String step : steps) {
            Job job = engine.getManagementService().createJobQuery().processInstanceId(pid).activityId(step).singleResult();
            assertNotNull(job, pid + ": the case waits at " + step);
            engine.getManagementService().executeJob(job.getId());
        }
    }

    static String billThroughCatalogueSteps(String bk) {
        String pid = startBill(bk);
        run(pid, CATALOGUE_STEPS);
        return pid;
    }

    static Object var(String pid, String name) {
        return engine.getRuntimeService().getVariable(pid, name);
    }

    static boolean catalogueHasForm(ProfileResolver r) {
        return !DocumentTypeCatalogue.of(r.resolve(TENANT, new ProfileBusinessClock(r, Clock.systemUTC()).today(TENANT)))
                .byReference(FORM).isEmpty();
    }

    @Test
    void aNewDocumentTypeIsAdoptedByNewCasesWhileARunningCaseKeepsItsBinding() {
        long deployments = engine.getRepositoryService().createDeploymentQuery().count();
        // a second node over the same CFG_* tables: it never sees this node's invalidate() (SL-120)
        ProfileResolver otherNode = new ProfileResolver(store, layers);
        assertFalse(catalogueHasForm(otherNode), "cached on the other node before the change");

        // a case started before the change, waiting at the asynchronous bl_type job
        String waiting = startBill("SL35/BILL/waiting");
        run(waiting, List.of("bl_profile"));
        String boundAtStart = String.valueOf(var(waiting, ConfigCaseBindingPlugin.VARIABLE));
        assertTrue(boundAtStart.contains(TENANT + "@" + rj.version()), boundAtStart);
        assertEquals(boundAtStart, store.caseBinding(TENANT, waiting).orElseThrow(), "CFG_CASE_BINDING row");
        assertEquals(boundAtStart, var(waiting, "profBinding"), "profile.binding reads the case's own binding");

        // before the change: the form is not in RJ's catalogue, so the bill fails its type lookup and check
        String before = billThroughCatalogueSteps("SL35/BILL/before");
        assertEquals("FAILED", var(before, "docStatus"));
        assertEquals("TYPE_UNKNOWN", var(before, "docReasonCode"));
        assertEquals(Boolean.FALSE, var(before, "docValid"), "no passing default without a catalogue type");

        // a version whose new type names a chain RJ does not define is refused at activation (catalogue check)
        makerChecker(rjWithNewType("2026.11.1", "NO_SUCH_CHAIN"));
        ConfigException refused = assertThrows(ConfigException.class,
                () -> admin.activate(TENANT, "2026.11.1", "rj.admin", List.of()));
        assertEquals("ACTIVATION_REFUSED", refused.code());
        assertTrue(refused.findings().stream().anyMatch(f -> f.code().equals("CHAIN_UNKNOWN")
                && f.path().equals("documentTypes." + TYPE)), "own scope validated with no scopes listed (SL-121): "
                + refused.findings());
        String stillOld = billThroughCatalogueSteps("SL35/BILL/refused");
        assertEquals("TYPE_UNKNOWN", var(stillOld, "docReasonCode"), "a refused version is never adopted");

        // the corrected version (RJ's own chain) activates; the next bill adopts the new type at bl_type and bl_check
        makerChecker(rjWithNewType("2026.11.2", "TREASURY_STD"));
        assertFalse(catalogueHasForm(otherNode), "approved, not yet active: the other node caches the old catalogue");
        admin.activate(TENANT, "2026.11.2", "rj.admin", List.of(TENANT));
        assertTrue(catalogueHasForm(otherNode), "the other node sees the activation through the change marker");
        String after = billThroughCatalogueSteps("SL35/BILL/after");
        assertEquals("COMPLETED", var(after, "docStatus"), "docReasonCode=" + var(after, "docReasonCode"));
        assertEquals(TYPE, var(after, "docId"));
        assertEquals("TREASURY_STD", var(after, "docApprovalChain"));
        assertEquals(Boolean.TRUE, var(after, "docValid"), "findings: " + var(after, "docFindings"));
        assertTrue(String.valueOf(var(after, ConfigCaseBindingPlugin.VARIABLE)).contains(TENANT + "@2026.11.2"),
                "a new case binds the new version: " + var(after, ConfigCaseBindingPlugin.VARIABLE));

        // the running case reaches bl_type and bl_check only now, and still reads the version it bound (GP-19 / R43)
        run(waiting, List.of("bl_type", "bl_cls", "bl_check"));
        assertEquals("FAILED", var(waiting, "docStatus"));
        assertEquals("TYPE_UNKNOWN", var(waiting, "docReasonCode"), "the running case keeps its catalogue");
        assertEquals(Boolean.FALSE, var(waiting, "docValid"));
        assertEquals(boundAtStart, var(waiting, ConfigCaseBindingPlugin.VARIABLE));

        // same deployment, same process definition: adopted as configuration, not as a pack redeploy
        assertEquals(deployments, engine.getRepositoryService().createDeploymentQuery().count(), "no redeploy");
        assertEquals(definitionOf(before), definitionOf(after));
        assertEquals(definitionOf(waiting), definitionOf(after));
    }

    static String definitionOf(String pid) {
        return engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).singleResult()
                .getProcessDefinitionId();
    }
}
