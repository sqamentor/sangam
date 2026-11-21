// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.cmmn.handler.CmmnHandlerContext;
import org.operaton.bpm.engine.impl.cmmn.handler.SentryHandler;
import org.operaton.bpm.engine.impl.cmmn.model.CmmnSentryDeclaration;
import org.operaton.bpm.engine.impl.cmmn.model.CmmnVariableOnPartDeclaration;
import org.operaton.bpm.model.cmmn.CaseFileItemTransition;
import org.operaton.bpm.model.cmmn.instance.CaseFileItem;
import org.operaton.bpm.model.cmmn.instance.CaseFileItemOnPart;
import org.operaton.bpm.model.cmmn.instance.IfPart;
import org.operaton.bpm.model.cmmn.instance.OnPart;
import org.operaton.bpm.model.cmmn.instance.Sentry;
import org.operaton.bpm.model.cmmn.instance.operaton.OperatonVariableOnPart;

/**
 * Owner ruling C1 (option A): CMMN 1.1 {@code caseFileItemOnPart}.
 * <p>
 * Operaton 2.2.0 {@code SentryHandler} logs "ignored unsupported attribute" for a case-file on-part and drops a sentry
 * whose only parts are case-file on-parts, so the guarded plan item starts at once. A case file item is a case
 * variable named by the item ({@link #variableName}); this handler turns each case-file on-part into one of Operaton's
 * own variable on-part declarations, so the engine's sentry evaluation fires it:
 * {@code create} on variable create, {@code update} and {@code replace} on variable update, {@code delete} on
 * variable delete. {@code addChild}, {@code removeChild}, {@code addReference} and {@code removeReference} have no
 * engine path and are refused at deploy.
 */
public class SangamSentryHandler extends SentryHandler {

    /** Case-file transitions refused at deploy (no engine path; owner ruling C1). */
    public static final Set<CaseFileItemTransition> NOT_SUPPORTED = Set.of(CaseFileItemTransition.addChild,
            CaseFileItemTransition.removeChild, CaseFileItemTransition.addReference, CaseFileItemTransition.removeReference);

    /** The case variable that carries a case file item: its {@code name}, else its id. */
    public static String variableName(CaseFileItem item) {
        return item.getName() != null && !item.getName().isBlank() ? item.getName().trim() : item.getId();
    }

    @Override
    public CmmnSentryDeclaration handleElement(Sentry sentry, CmmnHandlerContext context) {
        List<CaseFileItemOnPart> fileParts = new ArrayList<>();
        if (sentry.getOnParts() != null) {
            for (OnPart p : sentry.getOnParts()) {
                if (p instanceof CaseFileItemOnPart f) {
                    fileParts.add(f);
                }
            }
        }
        if (fileParts.isEmpty()) {
            return super.handleElement(sentry, context);
        }
        List<CmmnVariableOnPartDeclaration> parts = new ArrayList<>();
        for (CaseFileItemOnPart f : fileParts) {
            CaseFileItemTransition ev = standardEvent(f, sentry);
            CaseFileItem item = f.getSource();
            if (ev == null || item == null) {
                throw new ProcessEngineException("caseFileItemOnPart '" + f.getId() + "' of sentry '" + sentry.getId()
                        + "' needs a sourceRef and a standardEvent");
            }
            if (NOT_SUPPORTED.contains(ev)) {
                throw new ProcessEngineException("caseFileItemOnPart '" + f.getId() + "' of sentry '" + sentry.getId()
                        + "': transition '" + ev.name() + "' is not supported (addChild, removeChild, addReference and"
                        + " removeReference have no engine path; owner ruling C1)");
            }
            CmmnVariableOnPartDeclaration d = new CmmnVariableOnPartDeclaration();
            d.setVariableName(variableName(item));
            d.setVariableEvent(switch (ev) {
                case create -> "create";
                case delete -> "delete";
                default -> "update"; // update, replace
            });
            parts.add(d);
        }
        CmmnSentryDeclaration decl = new CmmnSentryDeclaration(sentry.getId());
        IfPart ifPart = sentry.getIfPart();
        if (ifPart != null && ifPart.getCondition() != null) {
            initializeIfPart(ifPart, decl, context);
        }
        List<OperatonVariableOnPart> own = queryExtensionElementsByClass(sentry, OperatonVariableOnPart.class);
        if (own != null && !own.isEmpty()) {
            initializeVariableOnParts(sentry, decl, context, own);
        }
        for (CmmnVariableOnPartDeclaration d : parts) {
            if (!decl.hasVariableOnPart(d.getVariableEvent(), d.getVariableName())) {
                decl.addVariableOnParts(d);
            }
        }
        if (context.getParent() != null) {
            context.getParent().addSentry(decl);
        }
        return decl;
    }

    /**
     * The {@code standardEvent} child of a case-file on-part. Operaton 2.2.0
     * {@code CaseFileItemOnPartImpl.getStandardEvent} throws a NullPointerException for a CMMN 1.1 document (its child
     * element is not resolved), so the element is read from the DOM in the on-part's own namespace.
     */
    static CaseFileItemTransition standardEvent(CaseFileItemOnPart f, Sentry sentry) {
        var dom = f.getDomElement();
        for (var child : dom.getChildElements()) {
            if ("standardEvent".equals(child.getLocalName())) {
                String text = child.getTextContent() == null ? "" : child.getTextContent().trim();
                try {
                    return CaseFileItemTransition.valueOf(text);
                } catch (IllegalArgumentException e) {
                    throw new ProcessEngineException("caseFileItemOnPart '" + f.getId() + "' of sentry '" + sentry.getId()
                            + "': unknown standardEvent '" + text + "'", e);
                }
            }
        }
        return null;
    }

    /** Already turned into a variable on-part by {@link #handleElement}; nothing to log as ignored. */
    @Override
    protected void initializeOnPart(CaseFileItemOnPart onPart, Sentry sentry, CmmnHandlerContext context) {
        // handled in handleElement
    }
}
