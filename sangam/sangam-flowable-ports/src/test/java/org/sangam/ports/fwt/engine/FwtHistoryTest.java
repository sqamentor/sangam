// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.fwt.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.ports.history.HistoryQueryService;
import org.sangam.ports.history.HistoryQueryService.Filter;
import org.sangam.ports.support.EngineTx;
import org.sangam.ports.support.Paging;

/** FW-16: FWT-017 (H2; 100k history variable rows). */
class FwtHistoryTest {

    // FWT-017
    @Test
    void fwt017_unfilteredVariablesListNeverCounts() {
        try (FwtEngine f = new FwtEngine()) {
            f.deploy("v.bpmn", FwtEngine.defs("""
                  <process id="v" isExecutable="true"><startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="t"/>
                    <userTask id="t"/><sequenceFlow id="f2" sourceRef="t" targetRef="e"/><endEvent id="e"/></process>"""));
            for (int i = 0; i < 100; i++) {
                Map<String, Object> vars = new HashMap<>();
                for (int j = 0; j < 1000; j++) {
                    vars.put("v" + j, j);
                }
                f.engine.getRuntimeService().startProcessInstanceByKey("v", vars);
            }
            assertEquals(100_000, f.engine.getHistoryService().createHistoricVariableInstanceQuery().count());
            HistoryQueryService h = new HistoryQueryService(f.engine, FwtEngine.D, EngineTx.transactions(f.engine));
            Filter none = new Filter(null, null, null, null, null, null, null, null, null, null, null, null);
            List<Long> ms = new ArrayList<>();
            Paging.PageResult<HistoryQueryService.VariableRow> page = null;
            for (int run = 0; run < 20; run++) {
                f.sql.clear();
                long t0 = System.nanoTime();
                page = h.variables(none, new Paging.PageRequest(run % 5, 50));
                ms.add((System.nanoTime() - t0) / 1_000_000);
                for (String s : f.sql) {
                    assertTrue(!s.toLowerCase(Locale.ROOT).contains("count("), "no COUNT(*) issued: " + s);
                }
                assertTrue(page.capped(), "capped=true");
                assertEquals((long) (run % 5) * 50 + 50 + 1, page.total(), "lower-bound total");
                assertEquals(50, page.items().size());
            }
            ms.sort(Long::compare);
            System.out.println("FWT-017 H2 100k rows, p95 ms = " + ms.get(18) + " (all " + ms + ")");
        }
    }
}
