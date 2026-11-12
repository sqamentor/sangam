// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.consumer.ifms;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.history.HistoricVariableInstance;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.jobexecutor.JobExecutor;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.task.IdentityLink;
import org.operaton.bpm.engine.task.Task;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.ledger.AccountKey;
import org.sangam.ledger.LedgerDefinition;
import org.sangam.ledger.LedgerRequest;
import org.sangam.ledger.LedgerService;
import org.sangam.onboarding.api.OnboardingRequest;
import org.sangam.onboarding.flow.OnboardingService;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;
import org.sangam.rest.modules.ledger.LedgerTenantDefinitions;
import org.sangam.security.SangamSecurity;
import org.sangam.security.admin.TenantAdminService;
import org.sangam.security.org.OrgModel;
import org.sangam.server.acceptance.AbstractAcceptanceIT;
import org.sangam.server.bridge.PacksTenantDeployer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * IFMS's first workflows (SD-434) as the generic packs of {@code sangam-packs}, run on the Tier-1 database for a fresh
 * department tenant of the Government of India, set up the way onboarding sets up a department: the tenant, its org
 * levels, the packs' offices and seats with their holders (the pack seeds), the packs deployed through the product
 * pack deployer, the year's appropriation loaded through the product ledger, the payee registered in the party
 * registry. Budget formulation, distribution and re-appropriation, and the ESS requests GPF, leave and pension. Every
 * open task is answered by the person who really holds the task's seat today (the product seat directory), with the
 * approving decision of the generic core blocks. Proof per case: it ends CLOSED with no incident, the approval chain
 * ran (each step a different person), and the pack audit trail has the case's records. This class knows no state or
 * department: the domain lives in the packs and the profiles.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IfmsPacksIT extends AbstractAcceptanceIT {

    /**
     * A department scope of the Government of India. The scope is the engine tenant id, and its profile chain (GOI > IN >
     * REGIME-WESTMINSTER-SUPPLY) supplies what the packs read through their connectors (classification, document types,
     * the BUDGET ledger), as for an onboarded central department. A tenant without a profile has none of it.
     */
    static final String TENANT = "GOI.ACC" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);

    static final Set<String> PACKS = Set.of("sangam.core", "gov.ifms.budget-formulation", "gov.ifms.budget-allocation",
            "gov.ifms.reappropriation", "gov.ifms.gpf", "gov.ifms.bill", "gov.ifms.pao-chain", "gov.leave", "gov.pension");

    /** The platform default org hierarchy (INTL security.orgLevels; packgen ORG_LEVELS). */
    static final List<String> ORG_LEVELS = List.of("HEAD_OFFICE", "REGIONAL_OFFICE", "FIELD_OFFICE");

    static final String SETUP = "acc.ifms.setup";
    /** The employee of the ESS requests: a person acting for himself, on no seat. */
    static final String EMPLOYEE = "E-1001";
    static final String HEAD = "2052-00-090-01";
    /** The bill's own default head of account (gov_ifms.py bl_defaults), which the GPF bill draws on. */
    static final String BILL_HEAD = "2054-00-001-01-00-13";

    @Autowired
    ProcessEngine engine;
    @Autowired
    PacksTenantDeployer packs;
    @Autowired
    DataSource dataSource;
    @Autowired
    ObjectProvider<AuditChain> auditChain;
    @Autowired
    ObjectProvider<LedgerTenantDefinitions> ledgers;
    @Autowired
    OnboardingService.Ports onboarding;
    @Autowired
    SangamSecurity security;
    @Autowired
    PackRegistry registry;
    @Autowired
    ConnectorRegistry connectors;

    /** The tenant's current financial year by its own fiscal calendar (the period label the ledger uses). */
    String currentYear;
    LedgerService ledger;
    /** Seed seats by role, for a task offered to a role group (no office on the case). */
    final Map<String, List<String>> seatsOfRole = new LinkedHashMap<>();

    /** Whether the server's job executor ran before this test paused it (restarted after the test). */
    private boolean executorWasActive;

    /**
     * The driver runs every due job itself ({@link #runJobs}). With the server's job executor also running, both took
     * the same job ids (OptimisticLockingException on the job rows, and the executor's operatonTaskExecutor colliding
     * with the driver; matrix-sqlserver.log, SL-89 analysis). As {@code GovCSupport} does, each test pauses the
     * executor, so jobs run only on the test's thread, in a known order, and restarts it afterwards for sibling ITs.
     */
    @BeforeEach
    void pauseJobExecutor() {
        JobExecutor je = jobExecutor();
        executorWasActive = je != null && je.isActive();
        if (executorWasActive) {
            je.shutdown();
        }
    }

    @AfterEach
    void resumeJobExecutor() {
        if (executorWasActive) {
            jobExecutor().start();
            executorWasActive = false;
        }
    }

    private JobExecutor jobExecutor() {
        return ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration()).getJobExecutor();
    }

    @BeforeAll
    void setUp() throws Exception {
        onboarding.tenants().create(new OnboardingRequest.TenantSpec(TENANT, "IFMS acceptance department", Map.of(),
                null, null, "government", null, null, null, Map.of(), null), SETUP);
        installSeatsAndHolders();
        packs.select(TENANT, PACKS);
        Map<String, String> deployed = packs.deploy(TENANT);
        System.out.println("[acceptance] ifms packs deployed for " + TENANT + ": " + deployed.keySet());
        assertThat(deployed).isNotEmpty();
        LedgerTenantDefinitions defs = ledgers.getObject();
        defs.ensure(TENANT);
        ledger = defs.ledger();
        currentYear = defs.period(TENANT, ledger.today(TENANT));
        // the distribution case takes its share from RELEASE, the re-appropriation finds savings at the consuming stage
        fund(HEAD, new BigDecimal("10000000"), "RELEASE", "-");
        // the GPF bill encumbers and consumes at the consuming stage of its DDO
        fund(BILL_HEAD, new BigDecimal("1000000"), null, "DDO-1");
        registerPayee();
    }

    // ------------------------------------------------------------------ given: the department as onboarding sets it up

    /**
     * Org levels, roles, offices and seats of the packs' seeds, and the seeds' holders posted to their seats. This is
     * what onboarding's installPackSeeds does (it posts no holders: real people are posted by the department), here
     * effective from the seeds' own date so that every task of today finds its holder.
     */
    private void installSeatsAndHolders() throws Exception {
        SecurityIdentity admin = SecurityIdentity.system(TENANT, SETUP)
                .withGroupsAndRoles(Set.of(), Set.of("system", TenantAdminService.PLATFORM_ADMIN_ROLE));
        ObjectMapper json = new ObjectMapper();
        Map<String, JsonNode> offices = new LinkedHashMap<>();
        Map<String, JsonNode> seats = new LinkedHashMap<>();
        Map<String, JsonNode> holders = new LinkedHashMap<>();
        Map<String, String> roles = new LinkedHashMap<>();
        for (String id : new TreeSet<>(PACKS)) {
            SangamPack p = registry.find(id).orElseThrow();
            for (PackDescriptor.PackResource r : p.descriptor().resources(PackDescriptor.PackResource.Kind.SEED)) {
                try (InputStream in = p.open(r.path())) {
                    JsonNode seed = json.readTree(in);
                    seed.path("offices").forEach(o -> offices.putIfAbsent(o.path("id").asText(), o));
                    seed.path("seats").forEach(s -> seats.putIfAbsent(s.path("id").asText(), s));
                    seed.path("holdings").forEach(h -> holders.putIfAbsent(h.path("seat").asText(), h));
                    seed.path("roles").forEach(ro -> roles.putIfAbsent(ro.path("code").asText(),
                            ro.path("name").path("en").asText(ro.path("code").asText())));
                }
            }
        }
        seats.values().forEach(s -> seatsOfRole.computeIfAbsent(s.path("role").asText(), k -> new ArrayList<>())
                .add(s.path("id").asText()));
        Instant from = LocalDate.parse("2026-01-01").atStartOfDay(ZoneId.of("UTC")).toInstant();
        Transactions.of(dataSource).inTransaction(c -> {
            security.orgUnits().defineLevels(c, admin, ORG_LEVELS);
            roles.forEach((code, name) -> security.roles().defineRole(c, admin, code, name, List.of()));
            Map<String, String> levelOf = new LinkedHashMap<>();
            seats.values().forEach(s -> levelOf.putIfAbsent(s.path("office").asText(), s.path("level").asText()));
            for (JsonNode o : offices.values()) {
                String parent = o.path("parent").asText("");
                security.orgUnits().createUnit(c, admin, o.path("id").asText(),
                        levelOf.getOrDefault(o.path("id").asText(), ORG_LEVELS.get(0)), parent.isBlank() ? null : parent,
                        o.path("id").asText(), null, from);
            }
            Set<String> made = new java.util.HashSet<>();
            List<JsonNode> pending = new ArrayList<>(seats.values());
            while (!pending.isEmpty()) {
                JsonNode next = pending.stream().filter(s -> s.path("reportsTo").isNull()
                        || made.contains(s.path("reportsTo").asText()) || !seats.containsKey(s.path("reportsTo").asText()))
                        .findFirst().orElse(pending.get(0));
                String up = next.path("reportsTo").asText(null);
                security.seats().createSeat(c, admin, next.path("id").asText(), next.path("role").asText(),
                        next.path("office").asText(), 1, false, up != null && made.contains(up) ? up : null, from);
                made.add(next.path("id").asText());
                pending.remove(next);
            }
            for (JsonNode h : holders.values()) {
                String person = h.path("person").asText();
                security.persons().provisionJit(c, TENANT, person, person, null);
                security.postings().post(c, admin, h.path("seat").asText(), person, OrgModel.PostingKind.REGULAR, from,
                        null, SETUP, null);
            }
            return null;
        });
    }

    /**
     * Given: the Appropriation Act of the current year for {@code head}, distributed down the chain the profile defines
     * (APPROPRIATION > ALLOCATION > RELEASE > AUTHORISATION > ... > the consuming stage); half stays at {@code keepAt};
     * the consuming stage is held by {@code holder}. Posted through the product ledger service, every posting checked.
     */
    private void fund(String head, BigDecimal amount, String keepAt, String holder) {
        LedgerDefinition def = ledger.definition(TENANT, "BUDGET");
        List<String> stages = def.stages().subList(0, def.stageIndex(def.consumeAt()) + 1);
        AccountKey at = new AccountKey(TENANT, "BUDGET", currentYear, head, "-", stages.get(0), null);
        post("ALLOCATE", at, null, amount);
        BigDecimal moving = amount;
        for (int i = 1; i < stages.size(); i++) {
            AccountKey next = at.atStage(stages.get(i), i == stages.size() - 1 ? holder : "-");
            post("DISTRIBUTE", at, next, moving);
            if (stages.get(i).equals(keepAt)) {
                moving = moving.divide(BigDecimal.valueOf(2));
            }
            at = next;
        }
    }

    private void post(String op, AccountKey from, AccountKey to, BigDecimal amount) {
        LedgerService.Result r = ledger.apply(new LedgerRequest(op, from, to, amount, null, null,
                "acc-setup:" + op + ":" + from.id() + (to == null ? "" : ">" + to.id()), "acc-setup:" + TENANT, SETUP,
                ledger.today(TENANT), false, false, false, Map.of()));
        assertThat(r.applied()).as("setup " + op + " " + from.id() + (to == null ? "" : " > " + to.id()) + ": "
                + r.reason() + " " + r.findings()).isTrue();
    }

    /** Given: the employee is a payee of the party registry (the registry.party connector the bill resolves). */
    private void registerPayee() {
        ConnectorResult r = connectors.require(TENANT, "registry.party").invoke(new ConnectorRequest(TENANT,
                UUID.randomUUID(), "acc-setup-1", 1, "acc-setup", "acc-setup/payee", "payee.register",
                Map.of("payee", "A Subscriber", "payeeAccount", "XXXX1001", "ifsc", "SBIN0000001", "payeeKind", "PERSON"),
                null));
        assertThat(r).as("payee registered: " + r).isInstanceOf(ConnectorResult.Success.class);
    }

    private BigDecimal available(String stage, String holder) {
        return ledger.available(new AccountKey(TENANT, "BUDGET", currentYear, HEAD, holder, stage, null));
    }

    // ------------------------------------------------------------------ driver

    /** Approving answers of the generic core blocks and the IFMS pack tasks (the packs' own vocabulary). */
    static final Map<String, Object> APPROVE = Map.of(
            "scrutinyDecision", "accept", "sanctionDecision", "approve", "recDecision", "recommend",
            "faDecision", "faConcur", "referralDecision", "faConcur", "objDecision", "accept",
            "serviceVerdict", "verified", "lifeDecision", "proceed", "remarks", "Seen; in order.",
            "joiningReport", "Rejoined duty on the due date.");

    /** External events delivered when a case waits for them (never cancellation or withdrawal). */
    static final Set<String> MESSAGES = Set.of("sangam.ifms.appropriationVoted");

    record Run(String pid, List<String> steps, List<String> seats, List<String> persons, Map<String, Object> finalVars) {
    }

    private String start(String key, String bk, Map<String, Object> vars) {
        return engine.getRuntimeService().createProcessInstanceByKey(key).businessKey(bk).processDefinitionTenantId(TENANT)
                .setVariables(vars).execute().getId();
    }

    /** The holder today of a seat (the product seat directory), else of the first seed seat of a pooled role group. */
    private String holder(String seat) {
        LocalDate today = LocalDate.now();
        return PackRuntime.seats().holderOf(TENANT, seat, today)
                .or(() -> seatsOfRole.getOrDefault(seat, List.of()).stream()
                        .map(s -> PackRuntime.seats().holderOf(TENANT, s, today)).flatMap(java.util.Optional::stream)
                        .findFirst())
                .orElse(null);
    }

    private Run drive(String key, String bk, Map<String, Object> vars) throws Exception {
        String pid = start(key, bk, vars);
        List<String> steps = new ArrayList<>();
        List<String> seats = new ArrayList<>();
        List<String> persons = new ArrayList<>();
        long end = System.currentTimeMillis() + 240_000;
        int idle = 0;
        while (System.currentTimeMillis() < end) {
            if (engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(pid).singleResult()
                    .getEndTime() != null) {
                break;
            }
            boolean moved = runJobs(bk);
            List<Task> tasks = engine.getTaskService().createTaskQuery().tenantIdIn(TENANT)
                    .processInstanceBusinessKeyLike(bk + "%").list();
            for (Task t : tasks) {
                String seat = engine.getTaskService().getIdentityLinksForTask(t.getId()).stream()
                        .map(IdentityLink::getGroupId).filter(java.util.Objects::nonNull).findFirst().orElse("none");
                // the real holder of the task's seat; a task back on the initiator's own seat is the initiator's
                String person = seat.equals(engine.getTaskService().getVariable(t.getId(), "initiatorSeat"))
                        ? String.valueOf(engine.getTaskService().getVariable(t.getId(), "initiatorPerson")) : holder(seat);
                assertThat(person).as(t.getTaskDefinitionKey() + ": nobody holds seat " + seat + " in " + TENANT).isNotNull();
                Map<String, Object> local = new HashMap<>();
                local.put("actingPerson", person);
                local.put("actingSeat", seat);
                engine.getTaskService().setVariablesLocal(t.getId(), local);
                engine.getTaskService().complete(t.getId(), new HashMap<>(APPROVE));
                steps.add(t.getTaskDefinitionKey());
                seats.add(seat);
                persons.add(person);
                moved = true;
            }
            // an external event the case waits for (for example the legislature's vote): deliver it as its source would
            for (var sub : engine.getRuntimeService().createEventSubscriptionQuery().tenantIdIn(TENANT).eventType("message")
                    .list()) {
                if (MESSAGES.contains(sub.getEventName()) && engine.getRuntimeService().createProcessInstanceQuery()
                        .processInstanceId(sub.getProcessInstanceId()).processInstanceBusinessKey(bk).count() == 1) {
                    engine.getRuntimeService().createMessageCorrelation(sub.getEventName()).processInstanceId(sub.getProcessInstanceId()).correlate();
                    steps.add("message:" + sub.getEventName());
                    moved = true;
                }
            }
            // nothing else can move and the case itself waits for a date (for example "leave ends"): let that time pass,
            // as GovBSupport / GovCSupport do; only intermediate timers of the case's own process, never a boundary
            // (escalation) timer or one inside a called core block
            if (!moved) {
                moved = advanceWaitingTimer(pid, steps);
            }
            if (!moved) {
                if (++idle > 120) {
                    break;
                }
                Thread.sleep(500);
            } else {
                idle = 0;
            }
        }
        Map<String, Object> fin = new LinkedHashMap<>();
        for (HistoricVariableInstance v : engine.getHistoryService().createHistoricVariableInstanceQuery()
                .processInstanceId(pid).list()) {
            fin.put(v.getName(), v.getValue());
        }
        return new Run(pid, steps, seats, persons, fin);
    }

    private boolean advanceWaitingTimer(String pid, List<String> steps) {
        Date now = new Date();
        for (Job j : engine.getManagementService().createJobQuery().processInstanceId(pid).timers().list()) {
            var jd = engine.getManagementService().createJobDefinitionQuery().jobDefinitionId(j.getJobDefinitionId())
                    .singleResult();
            if ("timer-intermediate-transition".equals(jd.getJobType()) && j.getDuedate() != null
                    && j.getDuedate().after(now)) {
                engine.getManagementService().setJobDuedate(j.getId(), now);
                steps.add("time-passes:" + jd.getActivityId());
                return true;
            }
        }
        return false;
    }

    private boolean runJobs(String bk) {
        assertThat(jobExecutor() == null || !jobExecutor().isActive())
                .as("the server's job executor must be paused while the driver runs jobs").isTrue();
        boolean ran = false;
        Date now = new Date();
        for (Job j : engine.getManagementService().createJobQuery().tenantIdIn(TENANT).executable().list()) {
            if (j.getDuedate() != null && j.getDuedate().after(now)) {
                continue;
            }
            try {
                engine.getManagementService().executeJob(j.getId());
                ran = true;
            } catch (RuntimeException e) {
                // the executor is paused, so this is the job's own failure: the engine has counted down its retries
                // and the driver runs it again until the last retry leaves an incident (asserted in assertClosed)
            }
        }
        return ran;
    }

    private void assertClosed(String what, Run r) {
        String incidents = engine.getRuntimeService().createIncidentQuery().processInstanceId(r.pid()).list().stream()
                .map(i -> i.getActivityId() + ": " + i.getIncidentMessage()).toList().toString();
        boolean ended = engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(r.pid())
                .singleResult().getEndTime() != null;
        Map<String, Object> facts = new LinkedHashMap<>();
        r.finalVars().forEach((k, v) -> {
            if (k.matches("(?i).*(outcome|route|state|reason|status|verdict|remarks|rule|findings).*")) {
                facts.put(k, v);
            }
        });
        String report = "[acceptance] " + what + " steps=" + r.steps() + " seats=" + r.seats() + " caseState="
                + r.finalVars().get("caseState") + " incidents=" + incidents + " active=" + (ended ? "[]"
                : engine.getRuntimeService().getActiveActivityIds(r.pid())) + " facts=" + facts;
        System.out.println(report + " ended=" + ended);
        assertThat(ended).as(report).isTrue();
        assertThat(incidents).as(report).isEqualTo("[]");
        assertThat(r.finalVars().get("caseState")).as(report).isEqualTo("CLOSED");
        assertThat(r.steps()).as("a human approval chain ran: " + report).isNotEmpty();
        assertThat(Set.copyOf(r.persons())).as("each step by a different person").hasSize(r.persons().size());
        assertThat(auditRows(r)).as("pack audit trail of the case").isPositive();
    }

    /** Rows of the case in the product audit chain (the packs' {@code Vars.audit} sink), by business key. */
    private long auditRows(Run r) {
        Object bk = engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(r.pid())
                .singleResult().getBusinessKey();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id = ? AND chain_id = ?")) {
            ps.setString(1, TENANT);
            ps.setString(2, String.valueOf(bk));
            long n;
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                n = rs.getLong(1);
            }
            AuditChain ac = auditChain.getIfAvailable();
            if (ac != null && n > 0) {
                AuditChain.VerifyResult v = ac.verify(c, TENANT, String.valueOf(bk));
                assertThat(v.ok()).as("audit chain of " + bk + " verifies: " + v.reason()).isTrue();
            }
            return n;
        } catch (Exception e) {
            throw new AssertionError("audit query failed: " + e.getMessage(), e);
        }
    }

    private static String bk(String p) {
        return p + "/" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Date date(int plusDays) {
        return Date.from(LocalDate.now().plusDays(plusDays).atStartOfDay(ZoneId.of("UTC")).toInstant());
    }

    /** Start variables of a case initiated by the real holder of {@code seat}. */
    private Map<String, Object> by(String seat, Object... kv) {
        Map<String, Object> v = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            v.put(kv[i].toString(), kv[i + 1]);
        }
        v.put("initiator", holder(seat));
        v.put("actingSeat", seat);
        assertThat(v.get("initiator")).as("nobody holds initiator seat " + seat).isNotNull();
        return v;
    }

    /** Start variables of an ESS request: the employee acts for himself, on no seat. */
    private static Map<String, Object> employee(Object... kv) {
        Map<String, Object> v = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            v.put(kv[i].toString(), kv[i + 1]);
        }
        v.put("initiator", EMPLOYEE);
        return v;
    }

    // ------------------------------------------------------------------ 1. budget

    @Test
    void budgetFormulation() throws Exception {
        Run r = drive("sangam-ifms-budget-formulation", bk("BF"), by("DDO-1", "fiscalYear", "2027-28",
                "headOfAccount", HEAD, "beAmount", 1_250_000_000L, "reAmount", 1_200_000_000L, // paise (SD-398)
                "justification", "Establishment estimates"));
        assertClosed("budget formulation", r);
    }

    @Test
    void budgetDistribution() throws Exception {
        // SL-57 (IFMS R-99 v0.2 s6.1): the Ministry side starts it (interim initiator seat FA-1, INFERRED), the Dealing
        // Hand enters it, the Pay and Accounts Officer checks and approves it; the limit moves into AUTHORISATION for
        // the DDO, out of RELEASE
        BigDecimal release = available("RELEASE", "-");
        BigDecimal ddo = available("AUTHORISATION", "DDO-1");
        Run r = drive("sangam-ifms-budget-allocation", bk("BA"), by("FA-1", "fiscalYear", currentYear,
                "headOfAccount", HEAD, "amount", 250_000_000L)); // paise: 25 lakh (SD-398)
        assertClosed("budget distribution", r);
        assertThat(r.seats().subList(0, 2)).as("entered by the Dealing Hand, approved by the PAO: " + r.steps())
                .containsExactly("DH-1", "PAO-1");
        assertThat(available("AUTHORISATION", "DDO-1").subtract(ddo)).as("DDO-1 received the limit in AUTHORISATION")
                .isEqualByComparingTo("2500000");
        assertThat(release.subtract(available("RELEASE", "-"))).as("taken from RELEASE").isEqualByComparingTo("2500000");
    }

    @Test
    void reappropriation() throws Exception {
        Run r = drive("sangam-ifms-reappropriation", bk("RA"), by("DDO-1", "fiscalYear", currentYear,
                "fromHead", HEAD, "toHead", "2052-00-090-02", "amount", 15_000_000L, "objectGroup", "OTHER_15CR",
                "justification", "Savings in salaries"));
        assertClosed("re-appropriation", r);
    }

    // ------------------------------------------------------------------ 2. ESS

    @Test
    void gpfAdvance() throws Exception {
        Run r = drive("sangam-ifms-gpf", bk("GPF"), employee("gpfAccount", "GPF-123", "gpfPurpose", "ADVANCE",
                "amount", 5_000_000L, "gpfBalance", 40_000_000L, "qualifyingService", 12.0, "employeeId", EMPLOYEE,
                "payee", "A Subscriber", "payeeAccount", "XXXX1001", "ifsc", "SBIN0000001"));
        assertClosed("GPF advance", r);
    }

    @Test
    void leave() throws Exception {
        // given: the employee's earned-leave account, credited by the pack's own annual credit (GP-48 balance ledger)
        String credit = start("sangam-leave-credit", bk("LC"), new HashMap<>(Map.of("key", EMPLOYEE)));
        assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(credit).singleResult()
                .getEndTime()).as("annual earned-leave credit for " + EMPLOYEE + " completed").isNotNull();
        Run r = drive("sangam-leave", bk("LV"), employee("employeeId", EMPLOYEE, "leaveType", "EL",
                "leaveFrom", date(1), "leaveTo", date(3), "leaveDays", 3));
        assertClosed("leave", r);
    }

    @Test
    void pensionSanctionAndStatus() throws Exception {
        Run r = drive("sangam-pension-sanction", bk("PEN"), employee("employeeId", EMPLOYEE,
                "employeeName", "A Pensioner", "retirementDate", date(60), "qualifyingService", 33.0, "lastPay", 900_000_000L,
                "bankAccount", "XXXX5678", "ifsc", "SBIN0000001"));
        assertClosed("pension sanction", r);
    }
}
