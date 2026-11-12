// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.api;

import java.util.Optional;

/**
 * Where {@link ConfigConnector} finds the profile binding a case stored at its start (SL-119, GP-19 / R43): the
 * binding string of {@code ResolvedProfile.binding()}, keyed by tenant and root case id (the server binds it to
 * {@code CFG_CASE_BINDING} through {@code JdbcConfigStore.caseBinding}).
 */
@FunctionalInterface
public interface CaseBindings {

    /** No stored bindings: every call reads the profile versions in force. */
    CaseBindings NONE = (tenant, rootId) -> Optional.empty();

    Optional<String> binding(String tenantId, String rootId);
}
