// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.adapters;

import org.sangam.compliance.ports.CompliancePorts.ErasureOutcome;
import org.sangam.compliance.ports.CompliancePorts.ErasurePort;
import org.sangam.compliance.ports.CompliancePorts.ErasureStatus;
import org.sangam.integration.lifecycle.ErasureService;

import java.util.List;
import java.util.Map;

/**
 * {@link ErasurePort} over the integration module's {@link ErasureService} (VI.5.1, R23, X.2.3.8): the engine,
 * outbox, inbox, idempotency, connector journal, dead letters, audit payloads and documents are scrubbed there, with
 * tombstones and the legal-hold refusal. The compliance module only maps the outcome.
 *
 * <p>The actor passed by the retention scheduler is {@code system:retention}; the integrator grants that principal
 * {@code ERASE_DATA} through {@code LifecyclePorts.Authorizer} (README "Integration notes").
 */
public final class IntegrationErasureAdapter implements ErasurePort {

    private final ErasureService erasure;

    public IntegrationErasureAdapter(ErasureService erasure) {
        this.erasure = erasure;
    }

    @Override
    public ErasureOutcome erase(String tenantId, String rootId, List<String> correlationKeys, String actor, String reason) {
        try {
            ErasureService.Result r = erasure.erase(tenantId, rootId, correlationKeys, actor, reason);
            return new ErasureOutcome(ErasureStatus.ERASED, r.scrubbed(), null);
        } catch (ErasureService.LegalHoldException e) {
            return new ErasureOutcome(ErasureStatus.REFUSED_LEGAL_HOLD, Map.of(), "LEGAL_HOLD");
        } catch (RuntimeException e) {
            return new ErasureOutcome(ErasureStatus.FAILED, Map.of(), e.getClass().getSimpleName());
        }
    }
}
