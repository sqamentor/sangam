// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;

import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.gov.DltDeliveryReports;
import org.sangam.integration.connector.gov.DltRegistrationStore;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.DltPort;
import org.sangam.rest.port.RequestContext;

/**
 * {@link DltPort} on sangam-integration (owner decisions 304 and 305): the signed delivery-report webhook goes to
 * {@link DltDeliveryReports#receive} and the registration routes to the stored per-tenant
 * {@link DltRegistrationStore}, always for the caller's tenant and with the caller as the audited actor.
 *
 * <p>A refusal of the webhook becomes a problem with the contract's code and a fixed detail: the route is reachable
 * before the caller is authenticated, so nothing the sender wrote is echoed. A registration the store's checks refuse
 * is {@code 422} with the store's reason as code ({@code ENTITY_INVALID}, {@code HEADER_INVALID},
 * {@code TEMPLATE_INVALID}, {@code URL_INVALID}).
 */
public final class IntegrationDltBridge implements DltPort {

    static final String SIGNATURE_DETAIL = "The delivery report is not authenticated: X-Sangam-Signature is missing,"
            + " does not verify, or is outside the accepted time window";
    static final String REPORT_DETAIL = "The body is not a delivery report or an array of delivery reports"
            + " (each needs a known status and clientRef or messageId)";

    private final DltDeliveryReports reports;
    private final DltRegistrationStore store;
    private final Runnable afterChange;

    /**
     * @param afterChange runs after a call changed the stored registration (the development simulator gateway learns
     *                    the new entries); never null
     */
    public IntegrationDltBridge(DltDeliveryReports reports, DltRegistrationStore store, Runnable afterChange) {
        this.reports = Objects.requireNonNull(reports, "reports");
        this.store = Objects.requireNonNull(store, "store");
        this.afterChange = Objects.requireNonNull(afterChange, "afterChange");
    }

    // ---------------------------------------------------------------- decision 304

    @Override
    public DeliveryReportResult deliveryReports(String tenant, String connectorId, Map<String, String> headers,
                                                byte[] body) {
        DltDeliveryReports.Answer a = reports.receive(tenant, connectorId, headers, body == null ? new byte[0] : body);
        return switch (a.status()) {
            case 200 -> {
                JsonNode n = Json.tree(a.body().getBytes(StandardCharsets.UTF_8));
                yield new DeliveryReportResult(n.path("accepted").asInt(), n.path("duplicates").asInt(),
                        n.path("ignored").asInt());
            }
            case 401 -> throw new ApiProblem(401, DltDeliveryReports.SIGNATURE_INVALID, SIGNATURE_DETAIL);
            case 404 -> throw new ApiProblem(404, "NOT_FOUND", "No DLT SMS connector with this id for this tenant");
            case 422 -> throw ApiProblem.unprocessable(DltDeliveryReports.REPORT_INVALID, REPORT_DETAIL);
            default -> throw new IllegalStateException("unexpected delivery-report answer " + a.status());
        };
    }

    // ---------------------------------------------------------------- decision 305

    @Override
    public Registration registration(RequestContext ctx) {
        return store.snapshot(ctx.tenantId())
                .map(s -> new Registration(s.entityId(), s.headers(),
                        s.templates().stream().map(t -> new Template(t.templateId(), t.header(), t.body())).toList(),
                        s.urlPrefixes(), s.revision()))
                .orElseGet(() -> new Registration(null, null, null, null, 0));
    }

    @Override
    public RegistrationChange setEntity(RequestContext ctx, EntityRequest request) {
        return change(() -> store.registerEntity(ctx.tenantId(), ctx.principalId(), request == null ? null : request.entityId()));
    }

    @Override
    public RegistrationChange addHeader(RequestContext ctx, HeaderRequest request) {
        return change(() -> store.registerHeader(ctx.tenantId(), ctx.principalId(), request == null ? null : request.header()));
    }

    @Override
    public RegistrationChange addTemplate(RequestContext ctx, Template request) {
        return change(() -> store.registerTemplate(ctx.tenantId(), ctx.principalId(),
                request == null ? null : request.templateId(), request == null ? null : request.header(),
                request == null ? null : request.body()));
    }

    @Override
    public RegistrationChange addUrlPrefix(RequestContext ctx, UrlPrefixRequest request) {
        return change(() -> store.registerUrl(ctx.tenantId(), ctx.principalId(), request == null ? null : request.prefix()));
    }

    private RegistrationChange change(Supplier<DltRegistrationStore.Change> work) {
        DltRegistrationStore.Change done;
        try {
            done = work.get();
        } catch (DltRegistrationStore.InvalidRegistration e) {
            String message = e.getMessage();
            String prefix = e.reason() + ": ";
            throw ApiProblem.unprocessable(e.reason(), message != null && message.startsWith(prefix)
                    ? message.substring(prefix.length()) : message);
        }
        if (done.changed()) {
            afterChange.run();
        }
        return new RegistrationChange(done.changed(), done.revision());
    }
}
