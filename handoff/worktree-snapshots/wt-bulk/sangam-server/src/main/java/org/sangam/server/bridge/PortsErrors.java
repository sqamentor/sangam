// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.Locale;
import java.util.function.Supplier;

import org.sangam.rest.adapter.operaton.OperatonSupport;
import org.sangam.rest.port.ApiProblem;

/**
 * Maps exceptions of sangam-flowable-ports / sangam-packs services to RFC 9457 problems. Engine exceptions go through
 * sangam-rest's {@link OperatonSupport#call} (including the {@code "<status> <CODE>: detail"} convention); the ports'
 * plain Java exceptions are classified here.
 */
final class PortsErrors {

    private PortsErrors() {
    }

    static <T> T call(Supplier<T> work) {
        try {
            return OperatonSupport.call(work);
        } catch (ApiProblem p) {
            throw p;
        } catch (org.sangam.ports.events.EventRegistry.InFlightException e) {
            throw ApiProblem.conflict("EVENT_IN_FLIGHT", e.getMessage())
                    .with("inbox", e.inFlight() == null ? null : e.inFlight().inbox())
                    .with("outbox", e.inFlight() == null ? null : e.inFlight().outbox());
        } catch (org.sangam.ports.batch.ProductBatchService.BatchStateException e) {
            throw ApiProblem.conflict("INVALID_BATCH_STATE", e.getMessage());
        } catch (SecurityException e) {
            throw ApiProblem.forbidden("ACTION_NOT_PERMITTED", e.getMessage());
        } catch (org.sangam.ports.support.PortsProblem p) {
            // Audit PR-2: the ports' stable codes keep their status: 413 PAYLOAD_TOO_LARGE, 403 TENANT_MISMATCH /
            // NOT_SANDBOX, 409 FENCING_TOKEN_REQUIRED, 422 PAGE_TOO_DEEP / EXPRESSION_NOT_ALLOWED / FRAGMENT_INVALID /
            // LIMIT_EXCEEDED / RESERVED_VARIABLE (and any future code with its own status).
            throw new ApiProblem(p.status(), p.code(), p.getMessage());
        } catch (IllegalArgumentException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            String l = m.toLowerCase(Locale.ROOT);
            if (l.startsWith("unknown") || l.contains("not found") || l.contains("not deployed") || l.contains("no such")) {
                throw new ApiProblem(404, "NOT_FOUND", m);
            }
            throw ApiProblem.unprocessable("VALIDATION_FAILED", m);
        } catch (IllegalStateException e) {
            throw ApiProblem.conflict("INVALID_STATE", e.getMessage());
        } catch (UnsupportedOperationException e) {
            throw ApiProblem.unprocessable("NOT_SUPPORTED", e.getMessage());
        }
    }

    static void run(Runnable work) {
        call(() -> {
            work.run();
            return null;
        });
    }

    static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "This action requires a reason (R42)");
        }
    }
}
