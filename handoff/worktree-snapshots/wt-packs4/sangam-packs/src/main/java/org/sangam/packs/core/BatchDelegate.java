// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.runtime.ProcessInstantiationBuilder;

import org.sangam.packs.port.BatchLedger;
import org.sangam.packs.port.PackRuntime;

/**
 * GP-09 rule: <b>no large multi-instance</b>. A set of items (beneficiaries, applicants, pensioners, sub-orders,
 * entities to recertify, discrepancy lines) is processed as one child process instance per item, started in chunks
 * from an asynchronous loop, and aggregated through the {@link BatchLedger} on a timer, so the parent root is never
 * hot (E6) and history stays linear. Field {@code op}:
 * <ul>
 *   <li>{@code LOAD}: queries {@code connector} for the item list (pension roll, beneficiary list, entities due for
 *       recertification) into {@code itemsVar}; the simulator, which has no such list, synthesises {@code sampleSize}
 *       items;</li>
 *   <li>{@code OPEN}: reads the items (JSON array of objects with {@code key} in {@code itemsVar}), opens round
 *       {@code batchRound} in the ledger, resets {@code fanOutCursor};</li>
 *   <li>{@code FAN_OUT}: starts up to {@code chunkSize} child instances of {@code childProcessKey} (business key
 *       {@code <parent>:<round>:<item>}, variables: the item fields + {@code inherit} list + batch ids); sets
 *       {@code fanOutDone} when every item is started. The BPMN loops this step with {@code asyncBefore}, so each chunk
 *       is its own transaction;</li>
 *   <li>{@code AGGREGATE}: reads the round summary: {@code batchComplete}, {@code batchSucceeded},
 *       {@code batchFailed}, {@code batchOutcomes} (JSON), {@code batchFailures} (JSON item to reason);</li>
 *   <li>{@code NOTIFY_ITEMS}: GP-09-safe broadcast: correlates {@code messageName} to the items of the round in chunks
 *       of {@code chunkSize} ({@code notifyCursor}, {@code notifyDone}), each with {@code selected} = item key in the
 *       comma list {@code selectedVar}; an item that already ended is skipped. With {@code roundToOpen} the same items are opened as
 *       a new ledger round and the round number is sent as {@code scoreRound} (bulk recompute, GP-47); {@code round}
 *       selects the round to read (also for {@code AGGREGATE}). Used instead of a signal, which would
 *       touch every subscriber in one transaction;</li>
 *   <li>{@code NEXT_ROUND}: GP-49 partial failure: the items of the failed set whose reason is in
 *       {@code retryReasons} (all failures when empty) become the next round's items ({@code batchRound + 1});
 *       {@code retryCount} is the number of items to resend.</li>
 * </ul>
 */
public class BatchDelegate implements JavaDelegate {

    private Expression op;
    private Expression itemsVar;
    private Expression childProcessKey;
    private Expression chunkSize;
    private Expression inherit;
    private Expression retryReasons;
    private Expression connector;
    private Expression sampleSize;
    private Expression messageName;
    private Expression selectedVar;
    private Expression itemsAreBusinessKeys;
    private Expression round;
    private Expression roundToOpen;

    @Override
    public void execute(DelegateExecution ex) {
        BatchLedger ledger = PackRuntime.batches();
        String tenant = Vars.tenant(ex);
        String batchId = Vars.str(ex, "batchId", ex.getProcessBusinessKey() == null ? ex.getProcessInstanceId()
                : ex.getProcessBusinessKey());
        ex.setVariable("batchId", batchId);
        int round = Vars.intVal(ex, "batchRound", 1);
        ex.setVariable("batchRound", round);
        String iv = Vars.field(itemsVar, ex, "items");
        switch (Vars.field(op, ex, "AGGREGATE")) {
            case "OPEN" -> {
                List<Map<String, Object>> items = Vars.jsonList(ex, iv);
                List<String> keys = new ArrayList<>();
                for (Map<String, Object> it : items) {
                    Object k = it.get("key");
                    if (k == null) {
                        throw new ProcessEngineException("every batch item needs a 'key'");
                    }
                    keys.add(k.toString());
                }
                ledger.open(tenant, batchId, round, keys);
                ex.setVariable("fanOutCursor", 0);
                ex.setVariable("fanOutDone", keys.isEmpty());
                ex.setVariable("batchSize", keys.size());
                Vars.audit(ex, "BATCH_OPENED", Vars.map("batchId", batchId, "round", round, "items", keys.size()));
            }
            case "LOAD" -> load(ex, iv);
            case "FAN_OUT" -> fanOut(ex, tenant, batchId, round, iv);
            case "AGGREGATE" -> {
                int r = Vars.fieldInt(this.round, ex, round);
                BatchLedger.BatchSummary s = ledger.summary(tenant, batchId, r);
                ex.setVariable("batchComplete", s.complete());
                ex.setVariable("batchSucceeded", s.succeeded());
                ex.setVariable("batchFailed", s.done() - s.succeeded());
                ex.setVariable("batchDone", s.done());
                ex.setVariable("batchOutcomes", Vars.toJson(s.byOutcome()));
                Vars.setJsonDocument(ex, "batchFailures", ledger.failures(tenant, batchId, r));
                if (s.complete()) {
                    Vars.audit(ex, "BATCH_ROUND_COMPLETE", Vars.map("batchId", batchId, "round", r,
                            "total", s.total(), "succeeded", s.succeeded(), "byOutcome", s.byOutcome()));
                }
            }
            case "NEXT_ROUND" -> nextRound(ex, ledger, tenant, batchId, round, iv);
            case "NOTIFY_ITEMS" -> notifyItems(ex, ledger, tenant, batchId, round);
            default -> throw new ProcessEngineException("unknown batch op");
        }
    }

    private void load(DelegateExecution ex, String iv) {
        String c = Vars.field(connector, ex);
        Map<String, Object> p = Vars.map("period", Vars.str(ex, "period"), "scope", Vars.str(ex, "scope"));
        org.sangam.packs.port.ConnectorResult r = PackRuntime.gateway().query(new org.sangam.packs.port.ConnectorRequest(
                Vars.tenant(ex), c, "list", null, ex.getProcessBusinessKey(), ex.getProcessInstanceId(),
                Vars.withDirective(ex, c, p), Vars.hash(p)));
        Object items = r.data().get("items");
        if (items instanceof List<?> l) {
            Vars.setJsonDocument(ex, iv, l);
            return;
        }
        int n = Vars.fieldInt(sampleSize, ex, 3);
        List<Map<String, Object>> synth = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            synth.add(Vars.map("key", String.format("ITEM-%05d", i), "amount", 100_000L * i)); // i thousand rupees, in paise
        }
        Vars.setJsonDocument(ex, iv, synth);
    }

    private void fanOut(DelegateExecution ex, String tenant, String batchId, int round, String iv) {
        List<Map<String, Object>> items = Vars.jsonList(ex, iv);
        int cursor = Vars.intVal(ex, "fanOutCursor", 0);
        int chunk = Vars.fieldInt(chunkSize, ex, 200);
        String key = Vars.field(childProcessKey, ex);
        RuntimeService rs = ex.getProcessEngineServices().getRuntimeService();
        int end = Math.min(items.size(), cursor + chunk);
        for (int i = cursor; i < end; i++) {
            Map<String, Object> it = items.get(i);
            Map<String, Object> vars = new HashMap<>();
            for (String n : Vars.csv(Vars.field(inherit, ex))) {
                vars.put(n, ex.getVariable(n));
            }
            it.forEach((k, v) -> vars.put(k, Vars.engineValue(v)));
            vars.put("batchId", batchId);
            vars.put("batchRound", round);
            vars.put("itemKey", it.get("key").toString());
            vars.put("parentBusinessKey", ex.getProcessBusinessKey());
            vars.put("tenantId", tenant);
            String bk = batchId + ":" + round + ":" + it.get("key");
            ProcessInstantiationBuilder b = rs.createProcessInstanceByKey(key).businessKey(bk).setVariables(vars);
            if (tenant != null && ex.getTenantId() != null) {
                b = b.processDefinitionTenantId(tenant);
            }
            b.execute();
        }
        ex.setVariable("fanOutCursor", end);
        ex.setVariable("fanOutDone", end >= items.size());
    }

    private void notifyItems(DelegateExecution ex, BatchLedger ledger, String tenant, String batchId, int round) {
        int from = Vars.fieldInt(this.round, ex, round);
        List<String> keys = new ArrayList<>(ledger.itemData(tenant, batchId, from).keySet());
        keys.sort(String::compareTo);
        String open = Vars.field(roundToOpen, ex);
        if (open != null && cursor(ex) == 0) {
            ledger.open(tenant, batchId, Integer.parseInt(open), keys); // a new round over the same items (GP-47)
        }
        int cursor = Vars.intVal(ex, "notifyCursor", 0);
        int end = Math.min(keys.size(), cursor + Vars.fieldInt(chunkSize, ex, 200));
        String msg = Vars.field(messageName, ex);
        java.util.Set<String> selected = new java.util.HashSet<>(Vars.csv(Vars.str(ex, Vars.field(selectedVar, ex,
                "selectedKeys"))));
        boolean ownKeys = Vars.fieldBool(itemsAreBusinessKeys, ex, false);
        RuntimeService rs = ex.getProcessEngineServices().getRuntimeService();
        for (int i = cursor; i < end; i++) {
            String k = keys.get(i);
            String bk = ownKeys ? k : batchId + ":" + round + ":" + k;
            Map<String, Object> vars = new HashMap<>();
            vars.put("selected", selected.contains(k));
            vars.put("stage", msg);
            if (open != null) {
                vars.put("scoreRound", Integer.parseInt(open));
            }
            var b = rs.createMessageCorrelation(msg).processInstanceBusinessKey(bk).setVariables(vars);
            if (ex.getTenantId() != null) {
                b = b.tenantId(ex.getTenantId());
            }
            try {
                b.correlateWithResult();
            } catch (org.operaton.bpm.engine.MismatchingMessageCorrelationException e) {
                // the item has withdrawn or ended; nothing to notify
            }
        }
        ex.setVariable("notifyCursor", end >= keys.size() ? 0 : end);
        ex.setVariable("notifyDone", end >= keys.size());
    }

    private static int cursor(DelegateExecution ex) {
        return Vars.intVal(ex, "notifyCursor", 0);
    }

    private void nextRound(DelegateExecution ex, BatchLedger ledger, String tenant, String batchId, int round,
                           String iv) {
        Map<String, String> failures = ledger.failures(tenant, batchId, round);
        List<String> retry = Vars.csv(Vars.field(retryReasons, ex));
        List<Map<String, Object>> previous = Vars.jsonList(ex, iv);
        List<Map<String, Object>> next = new ArrayList<>();
        Map<String, Map<String, Object>> corrected = new HashMap<>();
        for (Map<String, Object> c : Vars.jsonList(ex, "correctedItems")) {
            corrected.put(String.valueOf(c.get("key")), c);
        }
        for (Map<String, Object> it : previous) {
            String k = String.valueOf(it.get("key"));
            String reason = failures.get(k);
            if (reason != null && (retry.isEmpty() || retry.contains(reason))) {
                Map<String, Object> item = new HashMap<>(it);
                Map<String, Object> fixed = ledger.itemData(tenant, batchId, round).get(k);
                if (fixed != null) {
                    fixed.forEach((fk, fv) -> {
                        if (fv != null) {
                            item.put(fk, fv); // corrections recorded by the item's desk (GP-49)
                        }
                    });
                }
                if (corrected.containsKey(k)) {
                    item.putAll(corrected.get(k));
                }
                item.put("previousFailure", reason);
                next.add(item);
            }
        }
        Vars.setJsonDocument(ex, iv, next);
        ex.setVariable("retryCount", next.size());
        ex.setVariable("batchRound", round + 1);
        if (!next.isEmpty()) {
            List<String> keys = next.stream().map(m -> String.valueOf(m.get("key"))).toList();
            ledger.open(tenant, batchId, round + 1, keys);
        }
        ex.setVariable("fanOutCursor", 0);
        ex.setVariable("fanOutDone", next.isEmpty());
        Vars.audit(ex, "BATCH_RETRY_ROUND", Vars.map("batchId", batchId, "round", round + 1, "items", next.size()));
    }
}
