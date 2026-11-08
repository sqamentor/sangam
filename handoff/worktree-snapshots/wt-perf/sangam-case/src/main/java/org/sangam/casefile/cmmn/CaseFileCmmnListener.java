// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.cmmn;

import org.operaton.bpm.engine.delegate.CaseExecutionListener;
import org.operaton.bpm.engine.delegate.DelegateCaseExecution;
import org.operaton.bpm.engine.impl.cmmn.model.CmmnActivity;
import org.operaton.bpm.engine.impl.cmmn.transformer.CmmnTransformListener;
import org.operaton.bpm.model.cmmn.instance.CasePlanModel;
import org.operaton.bpm.model.cmmn.instance.Milestone;
import org.operaton.bpm.model.cmmn.instance.PlanItem;
import org.sangam.casefile.CaseModule;
import org.sangam.casefile.common.CaseEvents;
import org.sangam.casefile.config.CaseConfig;
import org.sangam.casefile.config.CaseConfigRegistry;
import org.sangam.casefile.file.CaseFileService;
import org.sangam.casefile.support.Db;
import org.sangam.ports.support.EngineTx;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The case file on top of CMMN (N04). Registered as a post CMMN transform listener, it attaches built-in listeners to
 * every case plan model and milestone:
 * <ul>
 *   <li><b>case created</b>: binds the case instance to the file named by the variable {@code caseFileId}, or opens a
 *       new file of the case type whose {@code cmmnCaseKey} is the case definition key (or of the type named by the
 *       variable {@code caseFileType}); sets {@code caseFileId} and {@code caseNumber} on the case instance;</li>
 *   <li><b>milestone occurred</b>: a {@code MILESTONE_REACHED} timeline event;</li>
 *   <li><b>case completed / terminated</b>: a timeline event, and closes the file when {@code closeFileWithCase}.</li>
 * </ul>
 * Everything runs in the engine command's transaction (Q1). With no {@link CaseModule} installed the listeners do
 * nothing, so a deployment without the module stays valid.
 */
public class CaseFileCmmnListener implements CmmnTransformListener {

    public static final String VAR_CASE_FILE_ID = "caseFileId";
    public static final String VAR_CASE_FILE_TYPE = "caseFileType";
    public static final String VAR_CASE_NUMBER = "caseNumber";

    private final boolean closeFileWithCase;

    public CaseFileCmmnListener(boolean closeFileWithCase) {
        this.closeFileWithCase = closeFileWithCase;
    }

    @Override
    public void transformCasePlanModel(CasePlanModel casePlanModel, CmmnActivity activity) {
        activity.addBuiltInListener(CaseExecutionListener.CREATE, (CaseExecutionListener) this::onCreate);
        activity.addBuiltInListener(CaseExecutionListener.COMPLETE, (CaseExecutionListener) ce -> onEnd(ce, "CMMN_CASE_COMPLETED"));
        activity.addBuiltInListener(CaseExecutionListener.TERMINATE, (CaseExecutionListener) ce -> onEnd(ce, "CMMN_CASE_TERMINATED"));
    }

    @Override
    public void transformMilestone(PlanItem planItem, Milestone milestone, CmmnActivity activity) {
        activity.addBuiltInListener(CaseExecutionListener.OCCUR, (CaseExecutionListener) this::onMilestone);
    }

    void onCreate(DelegateCaseExecution ce) {
        if (!CaseModule.isInstalled()) {
            return;
        }
        CaseModule m = CaseModule.get();
        Connection c = EngineTx.connection();
        String tenant = Db.tenant(ce.getTenantId());
        Object fileId = ce.getVariable(VAR_CASE_FILE_ID);
        if (fileId != null) {
            m.cases().bindInstance(c, tenant, fileId.toString(), "cmmn", ce.getCaseInstanceId(), "system");
            ce.setVariable(VAR_CASE_NUMBER, m.cases().get(c, tenant, fileId.toString()).caseNumber());
            return;
        }
        String definitionKey = definitionKey(ce.getCaseDefinitionId());
        Object explicitType = ce.getVariable(VAR_CASE_FILE_TYPE);
        Optional<CaseConfig.CaseType> type = explicitType != null
                ? Optional.of(m.config().caseType(c, tenant, explicitType.toString()))
                : typeForCmmnKey(c, m.config(), tenant, definitionKey);
        if (type.isEmpty()) {
            return;
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        for (CaseConfig.Field f : type.get().fields()) {
            Object v = ce.getVariable(f.name());
            if (v != null) {
                attrs.put(f.name(), v instanceof java.util.Date d ? d.toInstant().toString() : v);
            }
        }
        Object title = ce.getVariable("caseTitle");
        CaseFileService.CaseFile cf = m.cases().open(c, tenant, new CaseFileService.OpenRequest(type.get().key(),
                title == null ? type.get().name() + " " + (ce.getCaseBusinessKey() == null ? "" : ce.getCaseBusinessKey()) : title.toString(),
                str(ce, "jurisdiction"), str(ce, "orgUnit"), str(ce, "orgCode"), str(ce, "ownerSeat"), str(ce, "priority"), null,
                ce.getCaseBusinessKey(), attrs, ce.getCaseInstanceId(), null), "system");
        ce.setVariable(VAR_CASE_FILE_ID, cf.caseId());
        ce.setVariable(VAR_CASE_NUMBER, cf.caseNumber());
    }

    void onMilestone(DelegateCaseExecution ce) {
        if (!CaseModule.isInstalled()) {
            return;
        }
        Object fileId = ce.getVariable(VAR_CASE_FILE_ID);
        if (fileId == null) {
            return;
        }
        CaseModule.get().events().record(EngineTx.connection(), Db.tenant(ce.getTenantId()), CaseEvents.CASE, fileId.toString(),
                "MILESTONE_REACHED", "system", ce.getActivityName() == null ? ce.getActivityId() : ce.getActivityName(),
                Db.kv("milestone", ce.getActivityId(), "cmmnCaseInstanceId", ce.getCaseInstanceId()), "ENGINE", null);
    }

    void onEnd(DelegateCaseExecution ce, String eventType) {
        if (!CaseModule.isInstalled()) {
            return;
        }
        Object fileId = ce.getVariable(VAR_CASE_FILE_ID);
        if (fileId == null) {
            return;
        }
        CaseModule m = CaseModule.get();
        Connection c = EngineTx.connection();
        String tenant = Db.tenant(ce.getTenantId());
        m.events().record(c, tenant, CaseEvents.CASE, fileId.toString(), eventType, "system", ce.getCaseInstanceId(),
                Db.kv("cmmnCaseInstanceId", ce.getCaseInstanceId()), "ENGINE", null);
        if (closeFileWithCase) {
            CaseFileService.CaseFile cf = m.cases().get(c, tenant, fileId.toString());
            if (cf.status() == CaseFileService.Status.OPEN && ce.getCaseInstanceId().equals(cf.cmmnCaseInstanceId())) {
                m.cases().close(c, tenant, cf.caseId(), "CMMN case " + (eventType.endsWith("COMPLETED") ? "completed" : "terminated"),
                        -1, "system");
            }
        }
    }

    static Optional<CaseConfig.CaseType> typeForCmmnKey(Connection c, CaseConfigRegistry config, String tenant, String key) {
        if (key == null) {
            return Optional.empty();
        }
        for (CaseConfigRegistry.ConfigEntry e : config.list(c, tenant, CaseConfig.CASE_TYPE)) {
            if (key.equals(Db.str(e.definition(), "cmmnCaseKey", null))) {
                return Optional.of(config.caseType(c, tenant, e.key()));
            }
        }
        return Optional.empty();
    }

    static String definitionKey(String definitionId) {
        if (definitionId == null) {
            return null;
        }
        int i = definitionId.indexOf(':');
        return i < 0 ? definitionId : definitionId.substring(0, i);
    }

    private static String str(DelegateCaseExecution ce, String name) {
        Object v = ce.getVariable(name);
        return v == null ? null : v.toString();
    }
}
