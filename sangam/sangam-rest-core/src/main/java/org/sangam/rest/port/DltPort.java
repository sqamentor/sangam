// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * India DLT SMS (owner decisions 304 and 305): the signed delivery-report webhook of a tenant's DLT SMS connector and
 * the administration of the tenant's stored DLT registration (principal entity, sender headers, content templates,
 * whitelisted URL prefixes). The implementation lives with sangam-integration (sangam-server binds it); without that
 * module every method keeps its default: the hook knows no connector (404) and the registration is
 * {@code 501 CAPABILITY_NOT_INSTALLED}.
 *
 * <p>A refusal is never an answer: the port throws an {@link ApiProblem} with the contract's code
 * ({@code WEBHOOK_SIGNATURE_INVALID}, {@code NOT_FOUND}, {@code DLT_DELIVERY_REPORT_INVALID}; {@code ENTITY_INVALID},
 * {@code HEADER_INVALID}, {@code TEMPLATE_INVALID}, {@code URL_INVALID}).
 */
public interface DltPort {

    /** The capability and module named by {@code 501 CAPABILITY_NOT_INSTALLED}. */
    String CAPABILITY = "dlt-registration";
    String MODULE = "sangam-integration";

    /** {@code DltDeliveryReportResult}: what a delivery-report post did. */
    record DeliveryReportResult(int accepted, int duplicates, int ignored) {
    }

    /** {@code DltTemplate}: one registered content template; also the body of the template registration. */
    record Template(String templateId, String header, String body) {
    }

    /** {@code DltRegistration}: the tenant's stored registration; {@code entityId} is absent until one is registered. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Registration(String entityId, List<String> headers, List<Template> templates, List<String> urlPrefixes,
                        long revision) {
        public Registration {
            headers = headers == null ? List.of() : List.copyOf(headers);
            templates = templates == null ? List.of() : List.copyOf(templates);
            urlPrefixes = urlPrefixes == null ? List.of() : List.copyOf(urlPrefixes);
        }
    }

    /** {@code DltRegistrationChange}: whether the call altered the registration, and the revision after it. */
    record RegistrationChange(boolean changed, long revision) {
    }

    /** {@code DltEntityRequest}. */
    record EntityRequest(String entityId) {
    }

    /** {@code DltHeaderRequest}. */
    record HeaderRequest(String header) {
    }

    /** {@code DltUrlPrefixRequest}. */
    record UrlPrefixRequest(String prefix) {
    }

    /**
     * {@code POST /api/v1/hooks/sms-dlt/{tenant}/{connectorId}/delivery-reports}. Public at the Sangam layer: the
     * {@code X-Sangam-Signature} header is the authentication and is checked before the body is read as reports; a
     * missing, wrong or stale signature is {@code 401 WEBHOOK_SIGNATURE_INVALID} and writes nothing.
     *
     * @param headers request headers, first value per name
     * @param body    the raw request body, exactly the bytes the signature covers
     */
    default DeliveryReportResult deliveryReports(String tenant, String connectorId, Map<String, String> headers,
                                                 byte[] body) {
        throw ApiProblem.notFound("DLT SMS connector", connectorId);
    }

    /** {@code GET /api/v1/admin/dlt-registration}: the caller's tenant's registration (empty when none is stored). */
    default Registration registration(RequestContext ctx) {
        throw ApiProblem.notInstalled(CAPABILITY, MODULE);
    }

    /** {@code PUT /api/v1/admin/dlt-registration/entity}; {@code 422 ENTITY_INVALID}. */
    default RegistrationChange setEntity(RequestContext ctx, EntityRequest request) {
        throw ApiProblem.notInstalled(CAPABILITY, MODULE);
    }

    /** {@code POST /api/v1/admin/dlt-registration/headers}; {@code 422 HEADER_INVALID}. */
    default RegistrationChange addHeader(RequestContext ctx, HeaderRequest request) {
        throw ApiProblem.notInstalled(CAPABILITY, MODULE);
    }

    /**
     * {@code POST /api/v1/admin/dlt-registration/templates}; {@code 422 TEMPLATE_INVALID}. Writes
     * {@code DLT_TEMPLATE_REGISTERED} in the transaction that stores the template.
     */
    default RegistrationChange addTemplate(RequestContext ctx, Template request) {
        throw ApiProblem.notInstalled(CAPABILITY, MODULE);
    }

    /** {@code POST /api/v1/admin/dlt-registration/urls}; {@code 422 URL_INVALID}. */
    default RegistrationChange addUrlPrefix(RequestContext ctx, UrlPrefixRequest request) {
        throw ApiProblem.notInstalled(CAPABILITY, MODULE);
    }
}
