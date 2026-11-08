// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.ports;

import org.sangam.geo.geofence.Geofence;
import org.sangam.geo.geofence.GeofenceChecker;
import org.sangam.geo.geofence.GeofenceResult;
import org.sangam.geo.model.GeoPoint;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Outbound ports of the field module. Each has an air-gapped default (ARCHITECTURE §1.9) so the module runs without
 * the engine or a stored jurisdiction layer; the integrator binds the real adapters in sangam-server.
 */
public final class FieldPorts {

    private FieldPorts() {
    }

    /** Geofence and jurisdiction checks (N13 → N05). Bound to {@code adapters.GeoServicePort} over sangam-geo. */
    public interface GeoPort {
        GeofenceResult checkFence(String tenantId, Geofence fence, GeoPoint point, Double accuracyMeters);

        Optional<String> jurisdictionAt(String tenantId, String layer, GeoPoint point);
    }

    /** Pure fallback: circle and polygon fences only; FEATURE fences cannot be resolved without stored layers. */
    public static final class PureGeoPort implements GeoPort {
        @Override
        public GeofenceResult checkFence(String tenantId, Geofence fence, GeoPoint point, Double accuracyMeters) {
            if (fence.kind() == Geofence.Kind.FEATURE) {
                return new GeofenceResult(fence.id(), GeofenceResult.Status.INDETERMINATE, Double.NaN,
                        accuracyMeters == null ? Double.NaN : accuracyMeters, "UNRESOLVED_FENCE");
            }
            return GeofenceChecker.check(fence, point, accuracyMeters);
        }

        @Override
        public Optional<String> jurisdictionAt(String tenantId, String layer, GeoPoint point) {
            return Optional.empty();
        }
    }

    /**
     * DMN evaluation (scoring, risk weights for random selection, inspector rotation). Bound to
     * {@code adapters.OperatonDecisionAdapter}. Returns the result list (one map per matched rule / output entry).
     */
    public interface DecisionPort {
        List<Map<String, Object>> evaluate(String tenantId, String decisionKey, Map<String, Object> variables);
    }

    /** Default when no engine is wired: any DMN-configured template fails loudly instead of scoring silently. */
    public static final DecisionPort NO_DECISIONS = (t, k, v) -> {
        throw new org.sangam.field.FieldException("DECISION_UNAVAILABLE", "no decision engine bound for decision " + k);
    };

    /** Domain event published after commit-relevant changes (wired to the outbox by the integrator, VI.2). */
    public interface FieldEvents {
        void publish(java.sql.Connection c, String tenantId, String type, String entityId, Map<String, Object> payload);
    }

    public static final FieldEvents NO_EVENTS = (c, t, type, id, p) -> { };
}
