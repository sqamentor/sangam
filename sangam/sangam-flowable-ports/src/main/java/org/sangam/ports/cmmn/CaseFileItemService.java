// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import java.io.Serializable;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.delegate.CaseVariableListener;
import org.operaton.bpm.engine.delegate.DelegateCaseVariableInstance;
import org.operaton.bpm.engine.delegate.VariableListener;
import org.operaton.bpm.engine.impl.cmmn.model.CmmnActivity;
import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.operaton.bpm.model.cmmn.instance.Case;
import org.operaton.bpm.model.cmmn.instance.CaseFileItem;
import org.operaton.bpm.model.cmmn.instance.CaseFileModel;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.TenantKey;
import org.sangam.ports.support.EngineTx;

/**
 * Owner ruling C1 (option A): CMMN 1.1 case file items.
 * <p>
 * A case file item is a case variable named by the item ({@link SangamSentryHandler#variableName}); case-file
 * on-parts fire through Operaton's variable on-parts ({@link SangamSentryHandler}). The item's CMMN lifecycle state
 * ({@code AVAILABLE} after create / update / replace, {@code DISCARDED} after delete) is kept in
 * {@code PORT_CASE_FILE_ITEM}. Only the engine writes that table: a built-in case variable listener on the case plan
 * model ({@link #attach}) records each transition inside the engine transaction. There is no API that writes it.
 */
public class CaseFileItemService {

    public static final String TABLE = "PORT_CASE_FILE_ITEM";

    public record CaseFileItemState(String tenantId, String caseInstanceId, String itemId, String variableName,
                                    String definitionType, String state, Instant updatedAt) {
    }

    /** One case file item of a case definition, as declared in its case file model. */
    record Item(String itemId, String definitionType) implements Serializable {
    }

    private static final String COLS = "tenant_id, case_inst_id, item_id, variable_name, definition_type, state, updated_at, version_";

    private final ProcessEngine engine;

    public CaseFileItemService(ProcessEngine engine) {
        this.engine = engine;
    }

    /** Called at transform time for the case plan model activity of {@code caseElement}. */
    static void attach(Case caseElement, CmmnActivity casePlanModel) {
        if (caseElement == null || caseElement.getCaseFileModel() == null) {
            return;
        }
        Map<String, Item> byVariable = new LinkedHashMap<>();
        collect(caseElement.getCaseFileModel(), byVariable);
        if (byVariable.isEmpty()) {
            return;
        }
        CaseVariableListener listener = new Recorder(Map.copyOf(byVariable));
        for (String ev : List.of(VariableListener.CREATE, VariableListener.UPDATE, VariableListener.DELETE)) {
            casePlanModel.addBuiltInVariableListener(ev, listener);
        }
    }

    private static void collect(CaseFileModel model, Map<String, Item> out) {
        for (CaseFileItem item : model.getCaseFileItems()) {
            add(item, out);
        }
    }

    private static void add(CaseFileItem item, Map<String, Item> out) {
        String type = item.getDefinitionRef() == null ? null : item.getDefinitionRef().getDefinitionType();
        out.put(SangamSentryHandler.variableName(item), new Item(item.getId(), type));
        if (item.getChildren() != null) {
            for (CaseFileItem child : item.getChildren().getCaseFileItems()) {
                add(child, out);
            }
        }
    }

    /** The engine-side writer of {@code PORT_CASE_FILE_ITEM}. */
    static final class Recorder implements CaseVariableListener, Serializable {
        private final Map<String, Item> items;

        Recorder(Map<String, Item> items) {
            this.items = items;
        }

        @Override
        public void notify(DelegateCaseVariableInstance v) {
            Item item = items.get(v.getName());
            if (item == null || v.getSourceExecution() == null) {
                return;
            }
            String caseId = v.getSourceExecution().getCaseInstanceId();
            String tenant = TenantKey.of(v.getSourceExecution().getTenantId());
            String state = VariableListener.DELETE.equals(v.getEventName()) ? "DISCARDED" : "AVAILABLE";
            Instant now = ClockUtil.getCurrentTime().toInstant();
            var c = EngineTx.connection();
            int n = Jdbc.update(c, "UPDATE " + TABLE + " SET state = ?, updated_at = ?, version_ = version_ + 1"
                    + " WHERE case_inst_id = ? AND item_id = ?", state, now, caseId, item.itemId());
            if (n == 0) {
                Jdbc.update(c, "INSERT INTO " + TABLE + " (" + COLS + ") VALUES (?,?,?,?,?,?,?,?)", tenant, caseId,
                        item.itemId(), v.getName(), item.definitionType(), state, now, 0L);
            }
        }
    }

    /** Lifecycle state of every case file item of a case instance that has had a transition (read-only). */
    public List<CaseFileItemState> states(String caseInstanceId) {
        return EngineTx.transactions(engine).inTransaction(c -> Jdbc.query(c,
                "SELECT " + COLS + " FROM " + TABLE + " WHERE case_inst_id = ? ORDER BY item_id",
                rs -> new CaseFileItemState(rs.getString("tenant_id"), rs.getString("case_inst_id"), rs.getString("item_id"),
                        rs.getString("variable_name"), rs.getString("definition_type"), rs.getString("state"),
                        Jdbc.instant(rs, "updated_at")),
                caseInstanceId));
    }
}
