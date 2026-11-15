// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PackDocumentSlotsPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Round-5 lane w2 item 3 (decision 269): {@code GET /packs/{id}/document-slots} answers the pack's pairs to a platform
 * pack administrator (tenant.admin and the sangam-admin group, the gate of every /packs/ route), 404 for an unknown
 * pack, 501 without the packs module, and nothing reaches the port without the gate.
 */
class PackDocumentSlotsControllerTest {

    static final RequestContext ADMIN = new RequestContext("platform", "pack-admin", Set.of(Permissions.GROUP_ADMIN),
            null, null, null, null, "127.0.0.1", "OIDC");
    static final RequestContext TENANT_ADMIN = new RequestContext("t1", "tenant-admin",
            Set.of(Permissions.GROUP_TENANT_ADMIN), null, null, null, null, "127.0.0.1", "OIDC");

    final List<String> calls = new ArrayList<>();

    final PackDocumentSlotsPort port = id -> {
        calls.add(id);
        return "core".equals(id) ? Optional.of(List.of(
                new PackDocumentSlotsPort.DocumentSlot("NOTING", "notingDocumentId"),
                new PackDocumentSlotsPort.DocumentSlot("SANCTION_NOTE", "sanctionDocumentId"))) : Optional.empty();
    };

    static PermissionPort holding(String... granted) {
        Set<String> set = Set.of(granted);
        return new PermissionPort() {
            @Override
            public boolean has(RequestContext ctx, String permission) {
                return set.contains(permission);
            }

            @Override
            public Set<String> permissions(RequestContext ctx) {
                return set;
            }

            @Override
            public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
                return false;
            }
        };
    }

    PackDocumentSlotsController controller(PackDocumentSlotsPort p, PermissionPort permissions) {
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        if (p != null) {
            bf.addBean("slots", p);
        }
        return new PackDocumentSlotsController(bf.getBeanProvider(PackDocumentSlotsPort.class), permissions);
    }

    @Test
    void aPlatformPackAdministratorReadsThePairsInManifestOrder() {
        assertEquals(List.of(new PackDocumentSlotsPort.DocumentSlot("NOTING", "notingDocumentId"),
                        new PackDocumentSlotsPort.DocumentSlot("SANCTION_NOTE", "sanctionDocumentId")),
                controller(port, holding(Permissions.TENANT_ADMIN)).documentSlots(ADMIN, "core"));
    }

    @Test
    void anUnknownPackIs404() {
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> controller(port, holding(Permissions.TENANT_ADMIN)).documentSlots(ADMIN, "nope"));
        assertEquals(404, p.status());
        assertEquals("NOT_FOUND", p.code());
    }

    @Test
    void withoutTenantAdminOrThePlatformGroupNothingReachesThePort() {
        ApiProblem noPermission = assertThrows(ApiProblem.class,
                () -> controller(port, holding(Permissions.PROCESS_READ)).documentSlots(ADMIN, "core"));
        assertEquals(403, noPermission.status());
        ApiProblem noGroup = assertThrows(ApiProblem.class,
                () -> controller(port, holding(Permissions.TENANT_ADMIN)).documentSlots(TENANT_ADMIN, "core"));
        assertEquals(403, noGroup.status(), "a tenant administrator is not a platform pack administrator");
        assertEquals("ACTION_NOT_PERMITTED", noGroup.code());
        assertTrue(calls.isEmpty());
    }

    @Test
    void withoutThePacksModuleItIsNotInstalled() {
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> controller(null, holding(Permissions.TENANT_ADMIN)).documentSlots(ADMIN, "core"));
        assertEquals(501, p.status());
        assertEquals("CAPABILITY_NOT_INSTALLED", p.code());
    }
}
