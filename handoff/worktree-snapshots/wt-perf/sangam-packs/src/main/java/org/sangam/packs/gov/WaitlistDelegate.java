// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.gov;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;

import org.sangam.packs.core.Vars;

/**
 * Waitlist promotion when a selected candidate declines, fails document verification or does not join (GP-28). The
 * vacated post keeps its allotted category: a reserved post goes to the next waitlisted candidate of that category, an
 * open (UR) post to the next waitlisted candidate of any category. Input {@code vacatedKey}; outputs
 * {@code promotedKey} (or {@code waitlistExhausted = true}), updated {@code meritList}, {@code waitlist} and
 * {@code selectedKeys}.
 */
public class WaitlistDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution ex) {
        String vacated = Vars.str(ex, "vacatedKey");
        List<Map<String, Object>> merit = Vars.jsonList(ex, "meritList");
        List<Map<String, Object>> wait = Vars.jsonList(ex, "waitlist");
        String allotted = "UR";
        List<Map<String, Object>> keep = new ArrayList<>();
        for (Map<String, Object> m : merit) {
            if (String.valueOf(m.get("key")).equals(vacated)) {
                allotted = String.valueOf(m.getOrDefault("allotted", "UR"));
            } else {
                keep.add(m);
            }
        }
        Map<String, Object> promoted = null;
        for (Map<String, Object> w : wait) {
            String cat = String.valueOf(w.getOrDefault("category", "UR"));
            if ("UR".equals(allotted) || cat.equals(allotted)) {
                promoted = w;
                break;
            }
        }
        if (promoted == null) {
            ex.setVariable("waitlistExhausted", true);
            ex.setVariable("promotedKey", null);
            Vars.audit(ex, "WAITLIST_EXHAUSTED", Vars.map("vacated", vacated, "category", allotted));
        } else {
            wait.remove(promoted);
            promoted.put("allotted", allotted);
            keep.add(promoted);
            ex.setVariable("waitlistExhausted", false);
            ex.setVariable("promotedKey", promoted.get("key").toString());
            Vars.audit(ex, "WAITLIST_PROMOTED", Vars.map("vacated", vacated, "promoted", promoted.get("key"),
                    "category", allotted));
        }
        Vars.setJsonDocument(ex, "meritList", keep);
        Vars.setJsonDocument(ex, "waitlist", wait);
        ex.setVariable("selectedKeys", Vars.joinCsv(keep.stream().map(m -> m.get("key").toString()).toList()));
    }
}
