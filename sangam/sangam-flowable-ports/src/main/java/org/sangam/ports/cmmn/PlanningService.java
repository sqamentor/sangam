// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cmmn.entity.runtime.CaseExecutionEntity;
import org.operaton.bpm.engine.impl.el.Expression;
import org.operaton.bpm.model.cmmn.CmmnModelInstance;
import org.operaton.bpm.model.cmmn.instance.ApplicabilityRule;
import org.operaton.bpm.model.cmmn.instance.DiscretionaryItem;
import org.operaton.bpm.model.cmmn.instance.PlanItem;
import org.operaton.bpm.model.cmmn.instance.PlanningTable;
import org.operaton.bpm.model.cmmn.instance.Role;
import org.operaton.bpm.model.cmmn.instance.Stage;
import org.operaton.bpm.model.cmmn.instance.TableItem;
import org.operaton.bpm.model.xml.instance.ModelElementInstance;
import org.sangam.ports.support.EngineTx;

/**
 * Owner rulings C2 / C3 (option B): CMMN 1.1 planning table, read side.
 * <p>
 * {@link #applicable} lists the discretionary items of a stage's (or the case plan model's) planning table, including
 * nested planning tables, that the caller may plan:
 * <ul>
 *   <li>every {@code applicabilityRule} of the item holds; the condition is evaluated by the engine's
 *       {@code ExpressionManager} on the stage's case execution. The same condition text is checked at deploy by the
 *       SL-160 expression policy (sangam-engine {@code ModelCodeAllowList} walks every element's text). A condition
 *       that fails to evaluate or is not a boolean counts as false (fail closed);</li>
 *   <li>when the item names {@code authorizedRoleRefs}, the caller holds one of those roles (role name = group id);
 *       an item without roles is open to every caller.</li>
 * </ul>
 * Planning (instantiating) a discretionary item is not supported: Operaton 2.2.0 has no runtime API for it, and the
 * owner ruled against reaching into engine internals for it (C3 = B). The rule's {@code contextRef} (case file item)
 * is not used.
 */
public class PlanningService {

    public record Caller(String userId, Set<String> groups) {
    }

    public record PlanningItem(String stageExecutionId, String discretionaryItemId, String definitionId, String name,
                               Set<String> authorizedRoles) {
    }

    private final ProcessEngine engine;

    public PlanningService(ProcessEngine engine) {
        this.engine = engine;
    }

    public List<PlanningItem> applicable(String stageOrCaseExecutionId, Caller caller) {
        return List.of();
    }

    private static void collect(PlanningTable table, CaseExecutionEntity exec, Set<String> groups,
                                org.operaton.bpm.engine.impl.el.ExpressionManager el, List<PlanningItem> out) {
        for (TableItem item : table.getTableItems()) {
            if (!applies(item, exec, groups, el)) {
                continue;
            }
            if (item instanceof PlanningTable nested) {
                collect(nested, exec, groups, el, out);
            } else if (item instanceof DiscretionaryItem d) {
                Set<String> roles = roles(d);
                out.add(new PlanningItem(exec.getId(), d.getId(), d.getDefinition() == null ? null : d.getDefinition().getId(),
                        d.getDefinition() == null ? d.getName() : d.getDefinition().getName(), roles));
            }
        }
    }

    private static Set<String> roles(TableItem item) {
        Set<String> roles = new LinkedHashSet<>();
        for (Role r : item.getAuthorizedRoles()) {
            roles.add(r.getName() != null && !r.getName().isBlank() ? r.getName() : r.getId());
        }
        return roles;
    }

    private static boolean applies(TableItem item, CaseExecutionEntity exec, Set<String> groups,
                                   org.operaton.bpm.engine.impl.el.ExpressionManager el) {
        Set<String> roles = roles(item);
        if (!roles.isEmpty() && roles.stream().noneMatch(groups::contains)) {
            return false;
        }
        for (ApplicabilityRule rule : item.getApplicabilityRule()) {
            if (rule.getCondition() == null) {
                continue;
            }
            String text = rule.getCondition().getText() != null ? rule.getCondition().getText() : rule.getCondition().getBody();
            if (text == null || text.isBlank()) {
                continue;
            }
            try {
                Expression e = el.createExpression(text.trim());
                if (!Boolean.TRUE.equals(e.getValue(exec))) {
                    return false;
                }
            } catch (RuntimeException ex) {
                return false;
            }
        }
        return true;
    }
}
