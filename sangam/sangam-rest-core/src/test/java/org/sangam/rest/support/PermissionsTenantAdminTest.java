// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * SL-166 (1): the custom-field routes define and retire with {@code tenant.admin} and list with {@code form.read}
 * ({@code TenantCustomFieldController}). A group that may define fields must be able to list them, so every default
 * group holding {@code tenant.admin} also holds {@code form.read}. Written for the test phase (not run here).
 */
class PermissionsTenantAdminTest {

    @Test
    void aTenantAdminCanListTheCustomFieldsItDefines() {
        Set<String> admin = Permissions.DEFAULT_GROUP_PERMISSIONS.get(Permissions.GROUP_TENANT_ADMIN);
        assertTrue(admin.contains(Permissions.TENANT_ADMIN));
        assertTrue(admin.contains(Permissions.FORM_READ), "sangam-tenant-admin must hold form.read");
    }

    @Test
    void everyGroupThatDefinesFieldsCanListThem() {
        for (Map.Entry<String, Set<String>> e : Permissions.DEFAULT_GROUP_PERMISSIONS.entrySet()) {
            if (e.getValue().contains(Permissions.TENANT_ADMIN)) {
                assertTrue(e.getValue().contains(Permissions.FORM_READ), e.getKey() + " holds tenant.admin but not form.read");
            }
        }
    }
}
