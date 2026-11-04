// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.gov;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

import org.sangam.packs.core.Vars;
import org.sangam.packs.port.PackRuntime;

/**
 * Merit list with reservation (GP-26) over the per-applicant results of a recruitment round (GP-09 aggregation), and
 * the reservation roster carried across recruitments (GP-48).
 *
 * <p>Inputs: the batch round's item data ({@code score}, {@code category}, {@code dob}, {@code pwd}) written by each
 * applicant instance; {@code vacancies} (JSON object category to posts, for example
 * {@code {"UR":10,"OBC":5,"SC":3,"ST":2,"EWS":2}}). Method (DoPT practice, simplified and configurable):
 * candidates are ranked by score, ties broken by earlier date of birth; open (UR) posts are filled first purely on
 * merit from all categories ("own merit" candidates of reserved categories are not counted against reserved posts);
 * each reserved category's posts are then filled from the remaining candidates of that category. Unfilled reserved
 * posts are reported as {@code carryForward} and posted to the roster ledger. Everyone else goes to the waitlist in
 * merit order (GP-28).
 *
 * <p>Outputs: {@code meritList} (JSON rows key, rank, category, allotted), {@code waitlist} (JSON), {@code selectedKeys}
 * (comma list), {@code carryForward} (JSON).
 */
public class MeritListDelegate implements JavaDelegate {

    private Expression minimumScore;

    @Override
    public void execute(DelegateExecution ex) {
        String tenant = Vars.tenant(ex);
        String batchId = Vars.str(ex, "batchId");
        int round = Vars.intVal(ex, "scoreRound", Vars.intVal(ex, "batchRound", 1));
        Map<String, Map<String, Object>> data = PackRuntime.batches().itemData(tenant, batchId, round);
        BigDecimal min = Vars.decimal(Vars.field(minimumScore, ex, "0"));
        List<Map<String, Object>> cands = new ArrayList<>();
        data.forEach((k, d) -> {
            BigDecimal score = Vars.decimal(d.get("score"));
            if (score != null && score.compareTo(min) >= 0) {
                Map<String, Object> c = new LinkedHashMap<>(d);
                c.put("key", k);
                c.put("score", score);
                cands.add(c);
            }
        });
        cands.sort(Comparator.<Map<String, Object>, BigDecimal>comparing(c -> (BigDecimal) c.get("score"))
                .reversed().thenComparing(c -> String.valueOf(c.getOrDefault("dob", "9999-12-31"))));
        Map<String, Object> vac = Vars.jsonMap(ex, "vacancies");
        Map<String, Integer> left = new LinkedHashMap<>();
        vac.forEach((k, v) -> left.put(k, Vars.decimal(v).intValue()));
        List<Map<String, Object>> merit = new ArrayList<>();
        List<Map<String, Object>> rest = new ArrayList<>();
        int rank = 0;
        for (Map<String, Object> c : cands) {
            rank++;
            c.put("rank", rank);
            if (left.getOrDefault("UR", 0) > 0) {
                left.merge("UR", -1, Integer::sum);
                c.put("allotted", "UR");
                merit.add(c);
            } else {
                rest.add(c);
            }
        }
        List<Map<String, Object>> waitlist = new ArrayList<>();
        for (Map<String, Object> c : rest) {
            String cat = String.valueOf(c.getOrDefault("category", "UR"));
            if (!"UR".equals(cat) && left.getOrDefault(cat, 0) > 0) {
                left.merge(cat, -1, Integer::sum);
                c.put("allotted", cat);
                merit.add(c);
            } else {
                waitlist.add(c);
            }
        }
        List<String> selected = merit.stream().map(c -> c.get("key").toString()).toList();
        Map<String, Integer> carry = new LinkedHashMap<>();
        left.forEach((k, v) -> {
            if (v > 0 && !"UR".equals(k)) {
                carry.put(k, v);
                PackRuntime.ledgers().post(tenant, "roster", k, BigDecimal.valueOf(v),
                        batchId + ":carry-forward:" + k, true);
            }
        });
        ex.setVariable("meritList", Vars.toJson(merit));
        ex.setVariable("waitlist", Vars.toJson(waitlist));
        ex.setVariable("selectedKeys", Vars.joinCsv(selected));
        ex.setVariable("selectedCount", selected.size());
        ex.setVariable("carryForward", Vars.toJson(carry));
        Vars.audit(ex, "MERIT_LIST_PREPARED", Vars.map("candidates", cands.size(), "selected", selected.size(),
                "waitlisted", waitlist.size(), "carryForward", carry));
    }
}
