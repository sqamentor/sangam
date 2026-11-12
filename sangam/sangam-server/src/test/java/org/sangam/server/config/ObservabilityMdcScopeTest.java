// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.rest.web.Telemetry;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * SL-45, log channel: a request leaves no MDC key behind on its (pooled) thread. {@code Telemetry} mirrors the
 * business key, process instance, command type and idempotency key of a request into the MDC, and only the four keys
 * of the log-correlation filter were removed afterwards, so rerun 4 of {@code CrossTenantIsolationIT} logged tenant B's
 * {@code business_key} and {@code process_instance_id} on tenant A's later requests. The request filters of
 * {@link ObservabilityConfig}, in their registered order, must restore the thread's MDC.
 */
class ObservabilityMdcScopeTest {

    @AfterEach
    void clean() {
        MDC.clear();
    }

    /** Every request filter {@link ObservabilityConfig} registers, outermost first. */
    private static List<FilterRegistrationBean<?>> filters() throws Exception {
        ObservabilityConfig cfg = new ObservabilityConfig();
        List<FilterRegistrationBean<?>> out = new ArrayList<>();
        for (Method m : ObservabilityConfig.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Bean.class) && FilterRegistrationBean.class.isAssignableFrom(m.getReturnType())) {
                out.add((FilterRegistrationBean<?>) m.invoke(cfg));
            }
        }
        out.sort(Comparator.comparingInt(FilterRegistrationBean::getOrder));
        return out;
    }

    private static void request(FilterChain handler) throws Exception {
        List<FilterRegistrationBean<?>> regs = filters();
        FilterChain chain = handler;
        for (int i = regs.size() - 1; i >= 0; i--) {
            Filter f = regs.get(i).getFilter();
            FilterChain next = chain;
            chain = (rq, rs) -> f.doFilter(rq, rs, next);
        }
        chain.doFilter(new MockHttpServletRequest("POST", "/api/v1/process-instances"), new MockHttpServletResponse());
    }

    @Test
    void aRequestOfOneTenantLeavesNothingInTheMdcForTheNextRequestOnTheThread() throws Exception {
        MDC.put("thread", "kept");
        request((rq, rs) -> {
            // what sangam-rest tags on tenant B's REST start
            Telemetry.set(Telemetry.TENANT, "tenant-b");
            Telemetry.set(Telemetry.IDEMPOTENCY_KEY, "idempotency-of-b");
            Telemetry.businessKey("business-key-of-b");
            Telemetry.processInstance("instance-of-b");
            Telemetry.commandType("process.start");
        });
        assertEquals(Map.of("thread", "kept"), MDC.getCopyOfContextMap(),
                "the thread's MDC is as before the request: nothing of tenant B reaches the next request's log lines");
    }

    @Test
    void aFailingRequestLeavesNothingInTheMdcEither() throws Exception {
        try {
            request((rq, rs) -> {
                Telemetry.businessKey("business-key-of-b");
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // the handler failed; the MDC must still be restored
        }
        assertEquals(null, MDC.get(Telemetry.BUSINESS_KEY));
    }
}
