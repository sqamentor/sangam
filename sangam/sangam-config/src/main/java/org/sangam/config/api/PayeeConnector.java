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
 * (or any bank routing code), {@code payeeKind} (PERSON / ORGANISATION). Outputs (packs prefix them): {@code partyId}
 * (absent when the registry holds no such payee: {@code partyStatus} is then {@code NOT_FOUND}), {@code partyStatus},
 * {@code verified}, {@code multiple}, {@code matchCount}.
 */
public final class PayeeConnector implements SangamConnector {

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
            Map<String, Object> out = new LinkedHashMap<>();
            if (res.partyId() != null) {
                // a payee the registry does not hold has no party id; a connector answer holds no null value, and
                // putting one here made the answer itself fail, which read as a directory error (register E-26)
                out.put("partyId", res.partyId());
            }
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
