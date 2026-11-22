// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.api;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Connector for the pack connector id {@code registry.party}: operations {@code payee.resolve} (query) and
 * {@code payee.register} (submit). Inputs: {@code partyId}, {@code payee} (name), {@code payeeAccount}, {@code ifsc}
 * (or any bank routing code), {@code payeeKind} (PERSON / ORGANISATION). Outputs (packs prefix them): {@code partyId},
 * {@code partyStatus}, {@code verified}, {@code multiple}, {@code matchCount}. A payee the registry does not hold is
 * refused: business failure {@value #NOT_REGISTERED} with a message (fail-closed).
 */
public final class PayeeConnector implements SangamConnector {

    /** The refusal of a payee the party registry does not hold (fail-closed, register E-26 Follow-up 2). */
    public static final String NOT_REGISTERED = "PAYEE_NOT_REGISTERED";

    private final PayeeDirectory directory;

    public PayeeConnector(PayeeDirectory directory) {
        this.directory = directory;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return new ConnectorDescriptor("registry.party", "in-process", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                List.of("payee.resolve", "payee.register"), ConnectorProfile.LIVE);
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest r) {
        try {
            PayeeDirectory.Resolution res = "payee.register".equals(r.operation())
                    ? directory.register(r.tenantId(), r.input(), r.operationId().toString(), r.str("actor"))
                    : directory.resolve(r.tenantId(), r.input());
            if (res.partyId() == null) {
                // fail-closed (register E-26, Follow-up 2 (vi), EV-M-0964): a payee the registry does not hold is
                // refused with its own reason, never passed on as a completed answer; the bill's payee check then
                // reads FAILED and the bill is rejected, the refusal audited with this code (ConnectorCallDelegate)
                return ConnectorResult.BusinessFailure.of(NOT_REGISTERED, "the party registry holds no party for this"
                        + " payee" + (r.input().get("payeeAccount") == null ? " (no account given)" : " by its account")
                        + "; register the payee, then check again");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("partyId", res.partyId());
            // the party's status, never "status": the call step writes <prefix>Status as the call's own outcome and the
            // bill packs' payee check reads payeeStatus == 'COMPLETED' (ACTIVE there rejected every resolved payee)
            out.put("partyStatus", res.status());
            out.put("verified", res.verified());
            out.put("multiple", res.multipleBeneficiaries());
            out.put("matchCount", res.matches().size());
            return ConnectorResult.Success.of(out, res.partyId());
        } catch (RuntimeException e) {
            return ConnectorResult.BusinessFailure.of("PAYEE_DIRECTORY_ERROR", e.getMessage());
        }
    }
}
