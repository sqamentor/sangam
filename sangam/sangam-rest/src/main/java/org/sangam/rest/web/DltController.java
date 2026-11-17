// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.rest.port.DltPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * India DLT SMS (owner decisions 304 and 305).
 * <ul>
 *   <li>{@code POST /api/v1/hooks/sms-dlt/{tenant}/by-connector-id/delivery-reports?connectorId=}: the provider's signed
 *       delivery-report webhook. Public route (SecurityConfig): no Sangam token is asked for, the port verifies the
 *       {@code X-Sangam-Signature} of the raw body first and refuses with {@code 401 WEBHOOK_SIGNATURE_INVALID}
 *       before anything is read or written. The tenant is the one in the path, as for the other hooks; it only
 *       selects that tenant's connector and signing secret.</li>
 *   <li>{@code /api/v1/admin/dlt-registration}: the caller's tenant's stored DLT registration. Read needs
 *       {@code dlt.registration.read}, each change {@code dlt.registration.write} (tenant administration). A change
 *       writes {@code DLT_TEMPLATE_REGISTERED} in its own transaction (the port).</li>
 * </ul>
 * Every refusal is an {@code ApiProblem} of the port, written as RFC 9457 by {@link ProblemHandler}.
 */
@RestController
@RequestMapping("/api/v1")
public class DltController {

    private final DltPort dlt;
    private final PermissionPort permissions;

    public DltController(DltPort dlt, PermissionPort permissions) {
        this.dlt = dlt;
        this.permissions = permissions;
    }

    // ---------------------------------------------------------------- decision 304: delivery reports (public, signed)

    @PostMapping("/hooks/sms-dlt/{tenant}/by-connector-id/delivery-reports")
    public DltPort.DeliveryReportResult deliveryReports(@PathVariable String tenant, @RequestParam("connectorId") String connectorId,
                                                        @RequestBody(required = false) byte[] body,
                                                        HttpServletRequest request) {
        return dlt.deliveryReports(tenant, connectorId, EventFormController.headers(request),
                body == null ? new byte[0] : body);
    }

    // ---------------------------------------------------------------- decision 305: the tenant's registration

    @GetMapping("/admin/dlt-registration")
    public DltPort.Registration registration(RequestContext ctx) {
        permissions.require(ctx, Permissions.DLT_REGISTRATION_READ);
        return dlt.registration(ctx);
    }

    @PutMapping("/admin/dlt-registration/entity")
    public DltPort.RegistrationChange setEntity(RequestContext ctx, @RequestBody DltPort.EntityRequest body) {
        permissions.require(ctx, Permissions.DLT_REGISTRATION_WRITE);
        return dlt.setEntity(ctx, body);
    }

    @PostMapping("/admin/dlt-registration/headers")
    public DltPort.RegistrationChange addHeader(RequestContext ctx, @RequestBody DltPort.HeaderRequest body) {
        permissions.require(ctx, Permissions.DLT_REGISTRATION_WRITE);
        return dlt.addHeader(ctx, body);
    }

    @PostMapping("/admin/dlt-registration/templates")
    public DltPort.RegistrationChange addTemplate(RequestContext ctx, @RequestBody DltPort.Template body) {
        permissions.require(ctx, Permissions.DLT_REGISTRATION_WRITE);
        return dlt.addTemplate(ctx, body);
    }

    @PostMapping("/admin/dlt-registration/urls")
    public DltPort.RegistrationChange addUrlPrefix(RequestContext ctx, @RequestBody DltPort.UrlPrefixRequest body) {
        permissions.require(ctx, Permissions.DLT_REGISTRATION_WRITE);
        return dlt.addUrlPrefix(ctx, body);
    }
}
