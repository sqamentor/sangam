// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.flow;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.sangam.config.admin.ConfigAdminService;
import org.sangam.config.common.ConfigException;
import org.sangam.config.profile.ProfileDocument;
import org.sangam.config.profile.ProfileRepository;
import org.sangam.onboarding.api.DryRunReport;
import org.sangam.onboarding.api.OnboardingRequest;
import org.sangam.onboarding.api.OnboardingRequest.ConnectorBindingSpec;
import org.sangam.onboarding.api.OnboardingRun;
import org.sangam.onboarding.api.OnboardingRun.Status;
import org.sangam.onboarding.api.OnboardingRun.StepState;
import org.sangam.onboarding.api.OnboardingRun.StepStatus;
import org.sangam.onboarding.api.Problem;
import org.sangam.onboarding.api.StepId;
import org.sangam.onboarding.deps.PackDependencyResolver;
import org.sangam.onboarding.port.OnboardingPorts;
import org.sangam.onboarding.profile.ProfilePlanner;
import org.sangam.onboarding.readiness.OrgLevels;
import org.sangam.onboarding.readiness.ReadinessChecker;
import org.sangam.onboarding.support.OnboardingJson;
import org.sangam.packs.spi.SangamPack;

/**
 * The onboarding flow. {@link #dryRun} evaluates every step and the checklist without a write. {@link #start} runs the
 * BPMN process, whose service tasks call {@link #executeStep}. Each step is idempotent and skipped when already done for
 * the request revision, so a resumed run continues where it stopped. Configuration problems are collected and shown by
 * READINESS, which sends the run to the maker's fix task; only infrastructure failures become job retries and incidents.
 * Go-live needs a checker who is not the maker and who presents the hash of the readiness report they reviewed.
 */
public final class OnboardingService {

    /** The ports (compliance may be null when the module is off). */
    public record Ports(OnboardingPorts.TenantPort tenants, OnboardingPorts.PlacementPort placement,
                        OnboardingPorts.PackInstallPort packs, OnboardingPorts.ConnectorPort connectors,
                        OnboardingPorts.SecurityPort security, OnboardingPorts.SeedPort seeds,
                        OnboardingPorts.CompliancePort compliance, OnboardingPorts.AuditPort audit) {
    }

    private final OnboardingStore store;
    private final OnboardingFlowEngine flow;
    private final Ports ports;
    private final PackDependencyResolver resolver;
    private final ProfilePlanner planner;
    private final ReadinessChecker readiness;
    private final ConfigAdminService admin;
    private final ProfileRepository profiles;
    private final Clock clock;

    public OnboardingService(OnboardingStore store, OnboardingFlowEngine flow, Ports ports,
                             PackDependencyResolver resolver, ProfilePlanner planner, ReadinessChecker readiness,
                             ConfigAdminService admin, ProfileRepository profiles, Clock clock) {
        this.store = store;
        this.flow = flow;
        this.ports = ports;
        this.resolver = resolver;
        this.planner = planner;
        this.readiness = readiness;
        this.admin = admin;
        this.profiles = profiles;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    // ------------------------------------------------------------------ commands

    /** Reports every problem and the plan. Changes nothing. */
    public DryRunReport dryRun(OnboardingRequest req) {
        String hash = OnboardingJson.hash(OnboardingJson.tree(req));
        Map<StepId, Map<String, Object>> plan = new LinkedHashMap<>();
        List<Problem> problems = new ArrayList<>();

        Map<String, Object> t = new LinkedHashMap<>();
        Optional<OnboardingPorts.TenantPort.TenantInfo> existing = ports.tenants().find(req.tenantId());
        t.put("action", existing.isPresent() ? "exists: " + existing.get().status() : "create");
        plan.put(StepId.TENANT, t);
        if (!req.tenantId().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,59}")) {
            problems.add(Problem.error(StepId.TENANT, "INVALID_TENANT_ID", req.tenantId(), "1-60 safe characters"));
        }

        Map<String, Object> pl = new LinkedHashMap<>();
        Optional<OnboardingPorts.PlacementPort.Placement> placed = ports.placement().placement(req.tenantId());
        if (placed.isPresent()) {
            pl.put("cell", placed.get().cellId());
        } else {
            List<String> cands = ports.placement().candidates(req.tenant());
            pl.put("candidates", cands);
            if (cands.isEmpty()) {
                problems.add(Problem.error(StepId.PLACEMENT, "NO_CELL_QUALIFIES", req.tenantId(),
                        "no cell accepts this tenant (residency, capacity, headroom)"));
            }
        }
        plan.put(StepId.PLACEMENT, pl);

        ProfilePlanner.Plan profile = planner.plan(req, versionFor(Instant.now(clock), 1), LocalDate.now(clock));
        problems.addAll(profile.problems());
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("documents", profile.documents().stream().map(ProfileDocument::ref).toList());
        pr.put("chain", profile.resolved() == null ? List.of() : profile.resolved().chain());
        plan.put(StepId.PROFILE, pr);

        PackDependencyResolver.Resolution packs = resolve(req);
        problems.addAll(packs.problems());
        Map<String, Object> pk = new LinkedHashMap<>();
        pk.put("order", packs.order());
        pk.put("addedDependencies", packs.addedDependencies());
        pk.put("upgrades", OnboardingJson.MAPPER.convertValue(packs.upgrades(), List.class));
        plan.put(StepId.PACKS, pk);

        problems.addAll(checkBindings(req, packs));
        Map<String, Object> cn = new LinkedHashMap<>();
        cn.put("bindings", req.connectors().stream().map(b -> b.packConnector() + " -> " + b.connectorId()).toList());
        cn.put("required", new ArrayList<>(packs.connectors().keySet()));
        plan.put(StepId.CONNECTORS, cn);

        Map<String, Object> se = new LinkedHashMap<>();
        se.put("moduleDefaults", req.security().defaults());
        se.put("roles", req.security().roles().stream().map(OnboardingRequest.RoleSpec::id).toList());
        OrgLevels levels = OrgLevels.resolve(req, profile.resolved());
        se.put("orgLevels", levels.levels());
        se.put("orgLevelsSource", levels.source());
        plan.put(StepId.SECURITY, se);

        problems.addAll(checkSeeds(req));
        Map<String, Object> sd = new LinkedHashMap<>();
        sd.put("classifications", req.seeds().classifications().size());
        sd.put("identifierTypes", req.seeds().partyIdentifierTypes().size());
        sd.put("calendars", req.seeds().calendars().size());
        plan.put(StepId.SEEDS, sd);

        problems.addAll(checkCompliance(req));
        plan.put(StepId.COMPLIANCE, Map.of("jurisdictions", req.compliance().jurisdictions()));

        OnboardingRun.ReadinessReport report = readiness.check(req, profile.resolved(), packs, enabled(packs),
                List.of(), hash, 0, Instant.now(clock));
        problems.addAll(report.problems());
        return new DryRunReport(req.tenantId(), !Problem.anyError(problems), problems, plan, report, hash);
    }

    /** Starts a run. One open run per tenant. */
    public OnboardingRun start(OnboardingRequest req, String maker) {
        for (OnboardingRun r : store.list(req.tenantId())) {
            if (r.status() != Status.REJECTED && r.status() != Status.CANCELLED && r.status() != Status.LIVE) {
                throw new OnboardingException(409, "ONBOARDING_IN_PROGRESS", "run " + r.id() + " is " + r.status());
            }
        }
        Instant now = Instant.now(clock);
        String id = UUID.randomUUID().toString();
        OnboardingRun run = new OnboardingRun(id, req.tenantId(), Status.RUNNING, maker, null, 1, req,
                OnboardingJson.hash(OnboardingJson.tree(req)), null, null, null, now, now);
        store.save(run);
        audit(run, "ONBOARDING_STARTED", maker, Map.of("requestHash", run.requestHash()));
        String pid = flow.start(id);
        run = require(id).withProcess(pid, Instant.now(clock));
        store.save(run);
        return run;
    }

    /** Maker changes the request while the run waits for a fix, then the steps run again for the new revision. */
    public OnboardingRun amend(String runId, OnboardingRequest req, String maker) {
        OnboardingRun run = require(runId);
        if (run.status() != Status.WAITING_FOR_FIX && run.status() != Status.AWAITING_APPROVAL) {
            throw new OnboardingException(409, "INVALID_TRANSITION", "run is " + run.status());
        }
        if (!run.tenantId().equals(req.tenantId())) {
            throw new OnboardingException(422, "TENANT_MISMATCH", "a run cannot change its tenant");
        }
        OnboardingRun next = run.withRequest(req, OnboardingJson.hash(OnboardingJson.tree(req)), maker, Instant.now(clock))
                .withStatus(Status.RUNNING, Instant.now(clock));
        store.save(next);
        audit(next, "ONBOARDING_AMENDED", maker, Map.of("revision", next.revision(), "requestHash", next.requestHash()));
        if (run.status() == Status.WAITING_FOR_FIX) {
            flow.complete(run.processInstanceId(), OnboardingFlowEngine.TASK_FIX, Map.of());
        } else {
            // leaves the approval wait through the rejected path of the old revision is not wanted: restart the steps
            flow.cancel(run.processInstanceId(), "amended before approval");
            store.save(require(runId).withProcess(flow.start(runId), Instant.now(clock)));
        }
        return require(runId);
    }

    /** Runs the steps again without a change (after a connector or an external fix). */
    public OnboardingRun recheck(String runId, String actor) {
        OnboardingRun run = require(runId);
        if (run.status() != Status.WAITING_FOR_FIX) {
            throw new OnboardingException(409, "INVALID_TRANSITION", "run is " + run.status());
        }
        store.save(run.withStatus(Status.RUNNING, Instant.now(clock)));
        audit(run, "ONBOARDING_RECHECK", actor, Map.of());
        flow.complete(run.processInstanceId(), OnboardingFlowEngine.TASK_FIX, Map.of());
        return require(runId);
    }

    /** Checker approval, bound to the readiness report hash; the checker must not be the maker. */
    public OnboardingRun approve(String runId, String checker, String presentedHash) {
        OnboardingRun run = requireAwaiting(runId);
        if (checker == null || checker.equals(run.maker())) {
            throw new OnboardingException(422, "SEGREGATION_OF_DUTIES", "the checker must differ from the maker");
        }
        if (run.readiness() == null || !run.readiness().hash().equals(presentedHash)) {
            throw new OnboardingException(409, "APPROVAL_INVALIDATED", "the readiness report changed since it was reviewed");
        }
        Instant now = Instant.now(clock);
        store.save(run.withChecker(checker, now).withStatus(Status.APPROVED, now));
        audit(run, "GO_LIVE_APPROVED", checker, Map.of("readinessHash", presentedHash));
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("approved", true);
        vars.put("checker", checker);
        flow.complete(run.processInstanceId(), OnboardingFlowEngine.TASK_APPROVAL, vars);
        return require(runId);
    }

    public OnboardingRun reject(String runId, String checker, String reason) {
        OnboardingRun run = requireAwaiting(runId);
        if (checker == null || checker.equals(run.maker())) {
            throw new OnboardingException(422, "SEGREGATION_OF_DUTIES", "the checker must differ from the maker");
        }
        Instant now = Instant.now(clock);
        store.save(run.withChecker(checker, now).withStatus(Status.REJECTED, now));
        audit(run, "GO_LIVE_REJECTED", checker, Map.of("reason", reason == null ? "" : reason));
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("approved", false);
        vars.put("checker", checker);
        flow.complete(run.processInstanceId(), OnboardingFlowEngine.TASK_APPROVAL, vars);
        return require(runId);
    }

    /** Retries the failed step after its cause was fixed. */
    public OnboardingRun resume(String runId, String actor) {
        OnboardingRun run = require(runId);
        if (run.status() == Status.LIVE || run.status() == Status.REJECTED || run.status() == Status.CANCELLED) {
            throw new OnboardingException(409, "INVALID_TRANSITION", "run is " + run.status());
        }
        int n = flow.retryFailed(run.processInstanceId());
        audit(run, "ONBOARDING_RESUMED", actor, Map.of("jobs", n));
        if (run.status() == Status.FAILED) {
            store.save(run.withStatus(run.checker() == null ? Status.RUNNING : Status.APPROVED, Instant.now(clock)));
        }
        return require(runId);
    }

    public OnboardingRun cancel(String runId, String actor, String reason) {
        OnboardingRun run = require(runId);
        if (run.status() == Status.LIVE || run.status() == Status.REJECTED || run.status() == Status.CANCELLED) {
            throw new OnboardingException(409, "INVALID_TRANSITION", "run is " + run.status());
        }
        flow.cancel(run.processInstanceId(), reason == null ? "cancelled" : reason);
        store.save(run.withStatus(Status.CANCELLED, Instant.now(clock)));
        audit(run, "ONBOARDING_CANCELLED", actor, Map.of("reason", reason == null ? "" : reason));
        return require(runId);
    }

    public OnboardingRun get(String runId) {
        return require(runId);
    }

    public List<OnboardingRun> list(String tenantId) {
        return store.list(tenantId);
    }

    /** The newest run of a tenant that went live, else the newest. */
    public Optional<OnboardingRun> latest(String tenantId) {
        List<OnboardingRun> runs = store.list(tenantId);
        return runs.stream().filter(r -> r.status() == Status.LIVE).findFirst().or(() -> runs.stream().findFirst());
    }

    // ------------------------------------------------------------------ steps

    /** Called by the process. Returns true when the step found no error. An exception becomes a job retry. */
    public boolean executeStep(String runId, StepId step) {
        OnboardingRun run = require(runId);
        StepState state = run.step(step);
        if (state.status() == StepStatus.DONE && state.revision() == run.revision() && step != StepId.READINESS
                && !Problem.anyError(state.problems())) {
            return true;
        }
        Instant started = Instant.now(clock);
        store.save(run.withStep(step, new StepState(StepStatus.RUNNING, state.attempts() + 1, started, null,
                state.output(), state.problems(), state.revision()), started));
        List<Problem> problems = new ArrayList<>();
        Map<String, Object> output = new LinkedHashMap<>();
        try {
            switch (step) {
                case TENANT -> tenant(run, output, problems);
                case PLACEMENT -> placement(run, output, problems);
                case PROFILE -> profile(run, output, problems);
                case PACKS -> packs(run, output, problems);
                case CONNECTORS -> connectors(run, output, problems);
                case SECURITY -> security(run, output, problems);
                case SEEDS -> seeds(run, output, problems);
                case COMPLIANCE -> compliance(run, output, problems);
                case READINESS -> {
                    return readinessStep(runId, state, started);
                }
                case GO_LIVE -> goLive(run, output, problems);
                default -> throw new IllegalStateException("unknown step " + step);
            }
        } catch (RuntimeException e) {
            OnboardingRun now = require(runId);
            Instant at = Instant.now(clock);
            problems.add(Problem.error(step, "STEP_FAILED", step.name(), String.valueOf(e.getMessage())));
            store.save(now.withStep(step, new StepState(StepStatus.FAILED, state.attempts() + 1, started, at, output,
                    problems, run.revision()), at).withStatus(Status.FAILED, at));
            audit(run, "STEP_FAILED", "system", Map.of("step", step.name(), "error", String.valueOf(e.getMessage())));
            throw e;
        }
        Instant at = Instant.now(clock);
        OnboardingRun now = require(runId).withStep(step, new StepState(StepStatus.DONE, state.attempts() + 1, started,
                at, output, problems, run.revision()), at);
        if (step == StepId.GO_LIVE) {
            now = now.withStatus(Status.LIVE, at);
        } else if (now.status() == Status.FAILED) {
            now = now.withStatus(Status.RUNNING, at);
        }
        store.save(now);
        audit(run, "STEP_DONE", "system", Map.of("step", step.name(), "errors",
                problems.stream().filter(Problem::isError).count()));
        return !Problem.anyError(problems);
    }

    private void tenant(OnboardingRun run, Map<String, Object> out, List<Problem> problems) {
        OnboardingPorts.TenantPort.TenantInfo t = ports.tenants().create(run.request().tenant(), run.maker());
        out.put("tenant", t.id());
        out.put("status", t.status());
    }

    private void placement(OnboardingRun run, Map<String, Object> out, List<Problem> problems) {
        OnboardingRequest req = run.request();
        OnboardingPorts.PlacementPort.Placement p = ports.placement().placement(req.tenantId()).orElse(null);
        if (p == null) {
            if (ports.placement().candidates(req.tenant()).isEmpty()) {
                problems.add(Problem.error(StepId.PLACEMENT, "NO_CELL_QUALIFIES", req.tenantId(),
                        "no cell accepts this tenant (residency, capacity, headroom)"));
                return;
            }
            p = ports.placement().register(req.tenant(), run.maker());
        }
        out.put("cell", p.cellId());
        out.put("mode", p.mode());
    }

    private ProfilePlanner.Plan planOf(OnboardingRun run) {
        OnboardingRequest req = run.request();
        return planner.plan(req, versionFor(run.createdAt(), run.revision()), LocalDate.now(clock));
    }

    /** Monotonic across runs of a tenant, and numeric, so the newest version wins on the same effective date. */
    private static String versionFor(Instant created, int revision) {
        return "1." + created.getEpochSecond() + "." + revision;
    }

    private void profile(OnboardingRun run, Map<String, Object> out, List<Problem> problems) {
        ProfilePlanner.Plan plan = planOf(run);
        problems.addAll(plan.problems());
        List<String> stored = new ArrayList<>();
        if (!Problem.anyError(plan.problems())) {
            for (ProfileDocument d : plan.documents()) {
                Optional<ProfileRepository.Stored> s = profiles.get(d.id(), d.version());
                boolean same = s.isPresent() && s.get().hash().equals(d.contentHash());
                if (same && (s.get().status() == ProfileRepository.Status.ACTIVE
                        || s.get().status() == ProfileRepository.Status.SUBMITTED
                        || s.get().status() == ProfileRepository.Status.APPROVED)) {
                    stored.add(d.ref() + " (" + s.get().status() + ")");
                    continue;
                }
                try {
                    admin.saveDraft(d, run.maker());
                    admin.submit(d.id(), d.version(), run.maker());
                    stored.add(d.ref() + " (SUBMITTED)");
                } catch (ConfigException e) {
                    problems.add(Problem.error(StepId.PROFILE, e.code(), d.ref(), e.getMessage()));
                }
            }
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("profileScope", run.tenantId());
        config.put("jurisdictions", run.request().compliance().jurisdictions());
        if (plan.resolved() != null) {
            List<String> tags = new ArrayList<>();
            for (Map<String, Object> l : plan.resolved().list("locale.languages")) {
                tags.add(String.valueOf(l.get("tag")));
                if ("true".equals(String.valueOf(l.get("default")))) {
                    config.put("defaultLanguage", String.valueOf(l.get("tag")));
                }
            }
            config.put("languages", tags);
        }
        ports.tenants().configure(run.tenantId(), config, run.request().tenant().quotas(), run.maker());
        out.put("documents", stored);
    }

    private PackDependencyResolver.Resolution resolve(OnboardingRequest req) {
        return resolver.resolve(req.packs().ids(), req.packs().versions(), ports.packs().installed(req.tenantId()),
                req.packs().upgrades());
    }

    private List<SangamPack> enabled(PackDependencyResolver.Resolution r) {
        List<SangamPack> out = new ArrayList<>();
        r.versions().keySet().forEach(id -> resolver.registry().find(id).ifPresent(out::add));
        return out;
    }

    private void packs(OnboardingRun run, Map<String, Object> out, List<Problem> problems) {
        OnboardingRequest req = run.request();
        PackDependencyResolver.Resolution r = resolve(req);
        problems.addAll(r.problems());
        out.put("order", r.order());
        out.put("addedDependencies", r.addedDependencies());
        if (!r.ok()) {
            return;
        }
        ports.packs().select(req.tenantId(), new LinkedHashSet<>(r.order()));
        out.put("deployments", ports.packs().deploy(req.tenantId(), r.order()));
        if (!req.dmnOverrides().isEmpty()) {
            out.put("decisionOverrides", ports.packs().deployDecisionOverrides(req.tenantId(), req.dmnOverrides()));
        }
    }

    private List<Problem> checkBindings(OnboardingRequest req, PackDependencyResolver.Resolution packs) {
        List<Problem> out = new ArrayList<>();
        Set<String> available = new LinkedHashSet<>();
        ports.connectors().available(req.tenantId()).forEach(c -> available.add(c.id()));
        for (ConnectorBindingSpec b : req.connectors()) {
            if (!available.contains(b.connectorId())) {
                out.add(Problem.error(StepId.CONNECTORS, "CONNECTOR_NOT_FOUND", b.connectorId(), "no such connector for "
                        + b.packConnector() + "; install it or drop in a plugin"));
            }
            if (!packs.connectors().containsKey(b.packConnector())) {
                out.add(Problem.warn(StepId.CONNECTORS, "BINDING_UNUSED", b.packConnector(), "no enabled pack uses it"));
            }
        }
        return out;
    }

    private void connectors(OnboardingRun run, Map<String, Object> out, List<Problem> problems) {
        OnboardingRequest req = run.request();
        problems.addAll(checkBindings(req, resolve(req)));
        Map<String, Object> tests = new LinkedHashMap<>();
        for (ConnectorBindingSpec b : req.connectors()) {
            if (problems.stream().anyMatch(p -> p.isError() && b.connectorId().equals(p.subject()))) {
                continue;
            }
            ports.connectors().bind(req.tenantId(), b, run.maker());
            if (b.test() != null) {
                OnboardingPorts.ConnectorPort.TestResult t = ports.connectors().test(req.tenantId(), b, b.test().wantsInvoke());
                tests.put(b.packConnector(), t.mode() + ":" + t.outcome());
                if (!t.ok()) {
                    problems.add(Problem.error(StepId.CONNECTORS, "CONNECTOR_TEST_FAILED", b.packConnector(),
                            t.outcome() + ": " + t.detail()));
                }
            }
        }
        out.put("bound", req.connectors().size());
        out.put("tests", tests);
    }

    private void security(OnboardingRun run, Map<String, Object> out, List<Problem> problems) {
        OnboardingRequest.SecuritySpec s = run.request().security();
        if (s.defaults()) {
            ports.security().installModuleDefaults(run.tenantId(), s.selfServiceGrants(), run.maker());
        }
        out.put("roles", ports.security().defineRoles(run.tenantId(), s.roles(), run.maker()));
        List<org.sangam.packs.spi.PackDescriptor> packs = new ArrayList<>();
        enabled(resolver.resolve(run.request().packs().ids(), run.request().packs().versions(), Map.of(), true))
                .forEach(p -> packs.add(p.descriptor()));
        // SL-14: the org levels are fixed once the first unit exists (A02), so they are defined before any seat
        OrgLevels levels = OrgLevels.resolve(run.request(), planOf(run).resolved());
        List<Problem> levelProblems = levels.check(packs, ports.security().orgLevels(run.tenantId()),
                ports.security().orgUnitsExist(run.tenantId()));
        if (Problem.anyError(levelProblems)) {
            problems.addAll(levelProblems);
            return;
        }
        if (!levels.levels().isEmpty()) {
            ports.security().defineOrgLevels(run.tenantId(), levels.levels(), run.maker());
        }
        out.put("orgLevels", levels.levels());
        out.put("orgLevelsSource", levels.source());
        out.put("packSeats", ports.security().installPackSeeds(run.tenantId(), packs, run.maker()));
        if (!s.tenantAdmins().isEmpty()) {
            ports.security().bootstrapAdmins(run.tenantId(), s.tenantAdmins(), run.maker());
        }
    }

    private List<Problem> checkSeeds(OnboardingRequest req) {
        List<Problem> out = new ArrayList<>();
        for (Map<String, Object> s : req.seeds().calendars()) {
            if (s.get("id") == null) {
                out.add(Problem.error(StepId.SEEDS, "CALENDAR_INVALID", String.valueOf(s.get("name")), "a calendar needs an id"));
            }
        }
        for (Map<String, Object> s : req.seeds().classifications()) {
            if (s.get("content") == null) {
                String res = String.valueOf(s.get("resource"));
                try {
                    ports.seeds().readResource(res);
                } catch (RuntimeException e) {
                    out.add(Problem.error(StepId.SEEDS, "SEED_NOT_FOUND", res, "seed resource cannot be read"));
                }
            }
        }
        return out;
    }

    private void seeds(OnboardingRun run, Map<String, Object> out, List<Problem> problems) {
        OnboardingRequest req = run.request();
        problems.addAll(checkSeeds(req));
        if (Problem.anyError(problems)) {
            return;
        }
        int codes = 0;
        for (Map<String, Object> s : req.seeds().classifications()) {
            String text = s.get("content") != null ? String.valueOf(s.get("content"))
                    : ports.seeds().readResource(String.valueOf(s.get("resource")));
            String format = s.get("format") == null ? "csv" : String.valueOf(s.get("format"));
            String scope = s.get("scope") == null ? req.tenantId() : String.valueOf(s.get("scope"));
            codes += ports.seeds().loadClassifications(scope, format, text);
        }
        out.put("masterCodes", codes);
        if (!req.seeds().calendars().isEmpty()) {
            out.put("calendars", ports.seeds().saveCalendars(req.tenantId(), req.seeds().calendars()));
        }
        if (!req.seeds().partyIdentifierTypes().isEmpty()) {
            out.put("identifierTypes", ports.seeds().defineIdentifierTypes(req.tenantId(), req.seeds().partyIdentifierTypes()));
        }
    }

    private List<Problem> checkCompliance(OnboardingRequest req) {
        List<Problem> out = new ArrayList<>();
        if (req.compliance().jurisdictions().isEmpty()) {
            if (ports.compliance() != null && ports.compliance().jurisdictionOf(req.tenantId()).isEmpty()) {
                out.add(Problem.error(StepId.COMPLIANCE, "JURISDICTION_REQUIRED", req.tenantId(), "name compliance.jurisdictions"
                        + " in the request, or set sangam.compliance.jurisdictions for the deployment; none is assumed"));
            }
            return out;
        }
        if (ports.compliance() == null) {
            out.add(Problem.error(StepId.COMPLIANCE, "CAPABILITY_NOT_INSTALLED", "sangam-compliance", "the module is off"));
            return out;
        }
        Set<String> have = ports.compliance().installed();
        for (String j : req.compliance().jurisdictions()) {
            if (!have.contains(j)) {
                out.add(Problem.error(StepId.COMPLIANCE, "JURISDICTION_NOT_INSTALLED", j,
                        "no compliance jurisdiction pack with this code; installed: " + have));
            }
        }
        return out;
    }

    private void compliance(OnboardingRun run, Map<String, Object> out, List<Problem> problems) {
        OnboardingRequest req = run.request();
        problems.addAll(checkCompliance(req));
        if (!Problem.anyError(problems) && !req.compliance().jurisdictions().isEmpty()) {
            ports.compliance().enable(req.tenantId(), req.compliance().jurisdictions(), req.compliance().settings(), run.maker());
            out.put("jurisdiction", req.compliance().jurisdictions().get(0));
        }
    }

    private boolean readinessStep(String runId, StepState state, Instant started) {
        OnboardingRun run = require(runId);
        OnboardingRun.ReadinessReport report = evaluate(run);
        Instant at = Instant.now(clock);
        Status next = report.ready() ? Status.AWAITING_APPROVAL : Status.WAITING_FOR_FIX;
        store.save(run.withReadiness(report, at).withStep(StepId.READINESS, new StepState(StepStatus.DONE,
                state.attempts() + 1, started, at, Map.of("checks", report.checks()), report.problems(), run.revision()), at)
                .withStatus(next, at));
        audit(run, "READINESS_CHECKED", "system", Map.of("ready", report.ready(), "hash", report.hash()));
        return report.ready();
    }

    private OnboardingRun.ReadinessReport evaluate(OnboardingRun run) {
        List<Problem> earlier = new ArrayList<>();
        for (StepId s : StepId.values()) {
            if (s == StepId.READINESS || s == StepId.GO_LIVE) {
                continue;
            }
            earlier.addAll(run.step(s).problems());
        }
        OnboardingRequest req = run.request();
        ProfilePlanner.Plan plan = planOf(run);
        PackDependencyResolver.Resolution packs = resolver.resolve(req.packs().ids(), req.packs().versions(), Map.of(), true);
        return readiness.check(req, plan.resolved(), packs, enabled(packs), earlier, run.requestHash(), run.revision(),
                Instant.now(clock));
    }

    private void goLive(OnboardingRun run, Map<String, Object> out, List<Problem> problems) {
        OnboardingRequest req = run.request();
        String checker = run.checker();
        for (Map.Entry<String, String> e : req.goLive().evidence().entrySet()) {
            ports.tenants().recordEvidence(req.tenantId(), e.getKey(), e.getValue(), run.maker());
        }
        List<String> activated = new ArrayList<>();
        for (ProfileDocument d : planOf(run).documents()) {
            ProfileRepository.Stored s = profiles.get(d.id(), d.version()).orElseThrow(() ->
                    new IllegalStateException("profile " + d.ref() + " is not stored"));
            if (s.status() == ProfileRepository.Status.SUBMITTED) {
                admin.approve(d.id(), d.version(), checker, s.hash());
                s = profiles.get(d.id(), d.version()).orElseThrow();
            }
            if (s.status() == ProfileRepository.Status.APPROVED) {
                admin.activate(d.id(), d.version(), checker, d.id().equals(run.tenantId()) ? List.of(run.tenantId()) : List.of());
                activated.add(d.ref());
            }
        }
        out.put("activatedProfiles", activated);
        Optional<OnboardingPorts.TenantPort.TenantInfo> t = ports.tenants().find(req.tenantId());
        if (t.isEmpty() || !"ACTIVE".equals(t.get().status())) {
            ports.tenants().activate(req.tenantId(), checker);
        }
        out.put("tenantStatus", "ACTIVE");
        audit(run, "TENANT_LIVE", checker, Map.of("maker", run.maker(), "checker", checker));
    }

    // ------------------------------------------------------------------ helpers

    private OnboardingRun require(String runId) {
        return store.find(runId).orElseThrow(() -> new OnboardingException(404, "NOT_FOUND", "no onboarding run " + runId));
    }

    private OnboardingRun requireAwaiting(String runId) {
        OnboardingRun run = require(runId);
        if (run.status() != Status.AWAITING_APPROVAL) {
            throw new OnboardingException(409, "INVALID_TRANSITION", "run is " + run.status());
        }
        return run;
    }

    private void audit(OnboardingRun run, String action, String actor, Map<String, Object> details) {
        ports.audit().record(run.tenantId(), run.id(), action, actor, details);
    }
}
