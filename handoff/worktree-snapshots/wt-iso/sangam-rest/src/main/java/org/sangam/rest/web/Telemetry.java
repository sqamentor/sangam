// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import io.opentelemetry.api.trace.Span;

import org.sangam.rest.port.RequestContext;
import org.slf4j.MDC;

/**
 * OpenTelemetry span attributes (ARCHITECTURE §1.8, R13): operationId and businessKey on every span that handles
 * them, plus tenant, principal and idempotency key. Also mirrored into the SLF4J MDC for JSON logs.
 */
public final class Telemetry {

    public static final String TENANT = "sangam.tenant";
    public static final String PRINCIPAL = "enduser.id";
    public static final String SEAT = "sangam.seat";
    public static final String IDEMPOTENCY_KEY = "sangam.idempotency_key";
    public static final String BUSINESS_KEY = "sangam.business_key";
    public static final String OPERATION_ID = "sangam.operation_id";
    public static final String PROCESS_INSTANCE = "sangam.process_instance_id";
    public static final String COMMAND_TYPE = "sangam.command_type";
    public static final String WORK_ID = "sangam.work_id";

    private Telemetry() {
    }

    static void tagContext(RequestContext ctx) {
        set(TENANT, ctx.tenantId());
        set(PRINCIPAL, ctx.principalId());
        set(SEAT, ctx.seatId());
        set(IDEMPOTENCY_KEY, ctx.idempotencyKey());
    }

    public static void businessKey(String businessKey) {
        set(BUSINESS_KEY, businessKey);
    }

    public static void operationId(String operationId) {
        set(OPERATION_ID, operationId);
    }

    public static void processInstance(String id) {
        set(PROCESS_INSTANCE, id);
    }

    public static void commandType(String commandType) {
        set(COMMAND_TYPE, commandType);
    }

    public static void workId(String id) {
        set(WORK_ID, id);
    }

    public static void set(String key, String value) {
        if (value == null) {
            return;
        }
        Span.current().setAttribute(key, value);
        MDC.put(key, value);
    }
}
