// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/**
 * Decision 327 on the real security chain ({@link SecurityConfig#controlPlaneSecurity}): a cell token registers a cell
 * and sends its heartbeat, and it cannot open the cell for placement; only an ADMIN token reaches
 * {@code POST /api/v1/admin/cells/{cellId}/placement}. The request is run through the chain Spring Security builds
 * from the product's configuration; "reached" means the chain handed it on to the controller.
 */
class PlacementSwitchAuthorizationTest {

    static final String ADMIN = "cp-admin-token-0123456789";
    static final String CELL = "cp-cell-token-0123456789";
    static final String GATEWAY = "cp-gateway-token-0123456789";

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @Import(SecurityConfig.class)
    static class Chain {
        @Bean
        ControlPlaneProperties controlPlaneProperties() {
            return new ControlPlaneProperties(List.of(ADMIN + ":ADMIN", CELL + ":CELL", GATEWAY + ":GATEWAY"),
                    null, null, null, null, null, null, null, null);
        }
    }

    static AnnotationConfigWebApplicationContext context;
    static Filter chain;

    @BeforeAll
    static void start() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Chain.class);
        context.refresh();
        chain = context.getBean("springSecurityFilterChain", Filter.class);
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** The status the chain answers, or 0 when it let the request through to the controller. */
    static int answer(String method, String path, String query, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        if (query != null) {
            request.setQueryString(query);
            request.setParameter("open", "true");
        }
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        chain.doFilter(request, response, (req, res) -> reached.set(true));
        assertFalse(reached.get() && response.getStatus() >= 400, "either handed on or refused");
        return reached.get() ? 0 : response.getStatus();
    }

    @Test
    void aCellTokenRegistersAndBeatsButCannotOpenPlacement() throws Exception {
        assertEquals(0, answer("PUT", "/api/v1/cells/in-a", null, CELL), "the control: a cell token registers");
        assertEquals(0, answer("POST", "/api/v1/cells/in-a/heartbeat", null, CELL), "and sends its heartbeat");
        assertEquals(403, answer("POST", "/api/v1/admin/cells/in-a/placement", "open=true", CELL),
                "opening placement is not a cell's act");
    }

    @Test
    void onlyAnAdministratorOpensPlacement() throws Exception {
        assertEquals(0, answer("POST", "/api/v1/admin/cells/in-a/placement", "open=true", ADMIN));
        assertEquals(403, answer("POST", "/api/v1/admin/cells/in-a/placement", "open=true", GATEWAY));
        int anonymous = answer("POST", "/api/v1/admin/cells/in-a/placement", "open=true", null);
        assertTrue(anonymous == 401 || anonymous == 403, "no token: refused (" + anonymous + ")");
        int unknown = answer("POST", "/api/v1/admin/cells/in-a/placement", "open=true", "not-a-token");
        assertTrue(unknown == 401 || unknown == 403, "an unknown token: refused (" + unknown + ")");
    }
}