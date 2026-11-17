// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owner decision 404: the shared saved task lists of one tenant. Operaton's filters have no tenant (SL-73,
 * EngineRestTenantScope), so a tenant's lists are Sangam rows ({@code TEAM_TASK_FILTER}). Every method takes the
 * tenant explicitly and never returns a row of another tenant.
 */
public interface TaskFilterPort {

    /**
     * One saved task list.
     *
     * @param id         server-assigned id
     * @param name       display name
     * @param owner      optional owner (user or group) the list is meant for; informational
     * @param query      task query criteria (JSON object), as the shared task list screen sends them
     * @param properties display properties (JSON object, e.g. priority, description, refresh, variables)
     * @param createdBy  the creating principal
     * @param createdAt  database time of creation
     * @param version    row version, 1 on creation
     */
    record TaskFilter(String id, String name, String owner, Map<String, Object> query, Map<String, Object> properties,
                      String createdBy, Instant createdAt, int version) {
    }

    /** The tenant's lists, ordered by name then id. */
    List<TaskFilter> list(String tenantId);

    /** One list of the tenant; empty when it does not exist in that tenant. */
    Optional<TaskFilter> get(String tenantId, String id);

    /** Stores a new list of the tenant and returns it as stored. */
    TaskFilter create(String tenantId, String actor, String name, String owner, Map<String, Object> query,
                      Map<String, Object> properties);

    /**
     * Replaces name, owner, query and properties of a list of the tenant when its row version is still
     * {@code expectedVersion}, and returns it with the next version. Empty when the list does not exist in that tenant
     * (nothing of another tenant is touched); 409 REVISION_CONFLICT when it exists at another version.
     */
    Optional<TaskFilter> update(String tenantId, String id, int expectedVersion, String name, String owner,
                                Map<String, Object> query, Map<String, Object> properties);

    /**
     * Deletes a list of the tenant at {@code expectedVersion} and returns what was deleted. Empty when it does not exist
     * in that tenant; 409 REVISION_CONFLICT when it exists at another version.
     */
    Optional<TaskFilter> delete(String tenantId, String id, int expectedVersion);
}
