// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

/**
 * The two incident actions beyond retry and resolve that the incident preview ({@code GET /ops/incidents/{id}/preview})
 * already describes (O01, R63): <b>skip</b> moves the token past the failed activity (instance modification, R49; the
 * activity's effect does not happen), <b>cancel</b> cancels the incident's instance (business cancel, VI.5). Both
 * need a reason, run with the audit event ({@code INCIDENT_SKIPPED}, {@code INCIDENT_CANCELLED}) in the transaction of
 * the engine change, and return the incident as it was, as retry does.
 */
public interface IncidentActionPort {

    OpsPort.Incident skipIncident(RequestContext ctx, String id, OpsPort.IncidentAction action);

    OpsPort.Incident cancelIncident(RequestContext ctx, String id, OpsPort.IncidentAction action);
}
