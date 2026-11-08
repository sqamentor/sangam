// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.util.Map;
import java.util.Set;

/**
 * Declarative registries edited by the generic admin screens: service registry (R71, EC-07), SLA models (R79, EC-08),
 * routing strategies (R82), document templates (R74), conversation channels (R81), agents (R72) and retention classes
 * (R29, R61). Entries are JSON documents; the server adds {@code id}, {@code kind}, {@code revision},
 * {@code createdBy}, {@code updatedAt} and validates the fields each kind requires.
 */
public interface RegistryPort {

    Set<String> KINDS = Set.of("services", "sla-models", "routing-strategies", "templates", "conversation-channels",
            "agents", "retention-classes");

    Page<Map<String, Object>> list(RequestContext ctx, String kind, String q, Page.PageRequest page);

    Map<String, Object> get(RequestContext ctx, String kind, String id);

    Map<String, Object> create(RequestContext ctx, String kind, Map<String, Object> entry);

    /** A stale {@code revision} in the body gives {@code 409 REVISION_CONFLICT}. */
    Map<String, Object> update(RequestContext ctx, String kind, String id, Map<String, Object> entry);

    void delete(RequestContext ctx, String kind, String id, String reason);

    /** action: activate | retire | test (kind-specific). */
    Map<String, Object> action(RequestContext ctx, String kind, String id, String action, Map<String, Object> body);
}
