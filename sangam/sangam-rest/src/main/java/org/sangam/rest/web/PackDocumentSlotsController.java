// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.List;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PackDocumentSlotsPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner decision 269: {@code GET /api/v1/packs/by-id/document-slots?id=}, the documentType-to-slot pairs of one pack's DSC
 * steps. Same gate as the other pack routes ({@code tenant.admin} and the platform administrator group); {@code 404}
 * for an unknown pack, {@code 501 CAPABILITY_NOT_INSTALLED} without the packs module.
 */
@RestController
@RequestMapping("/api/v1/packs")
public class PackDocumentSlotsController {

    private final ObjectProvider<PackDocumentSlotsPort> slots;
    private final PermissionPort permissions;

    public PackDocumentSlotsController(ObjectProvider<PackDocumentSlotsPort> slots, PermissionPort permissions) {
        this.slots = slots;
        this.permissions = permissions;
    }

    @GetMapping("/by-id/document-slots")
    public List<PackDocumentSlotsPort.DocumentSlot> documentSlots(RequestContext ctx, @RequestParam("id") String id) {
        permissions.require(ctx, Permissions.TENANT_ADMIN);
        if (!ctx.inGroup(Permissions.GROUP_ADMIN)) {
            throw ApiProblem.notPermitted("platform pack administration");
        }
        PackDocumentSlotsPort port = slots.getIfAvailable();
        if (port == null) {
            throw ApiProblem.notInstalled("pack document slots", "sangam-packs");
        }
        return port.documentSlots(id).orElseThrow(() -> ApiProblem.notFound("pack", id));
    }
}
