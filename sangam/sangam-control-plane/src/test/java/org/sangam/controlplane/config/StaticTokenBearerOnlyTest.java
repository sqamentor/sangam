// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The precondition under which the control plane runs without CSRF protection: its only credential is the token in
 * the {@code Authorization: Bearer} header, which a browser never attaches by itself. The same valid token in a
 * cookie, in the query or in a form field authenticates nobody, and authenticating creates no session and sets no
 * cookie. (The session policy and the absence of cookie code are held by sangam-core's
 * {@code BearerOnlyAuthenticationGuardTest}.)
 */
class StaticTokenBearerOnlyTest {

    static final String TOKEN = "cp-admin-token-0123456789";

    final SecurityConfig.StaticTokenFilter filter =
            new SecurityConfig.StaticTokenFilter(SecurityConfig.parse(List.of(TOKEN + ":ADMIN")));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** Runs the filter and returns the authentication the rest of the chain would see. */
    Authentication authenticate(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        SecurityContextHolder.clearContext();
        AtomicReference<Authentication> seen = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication()));
        return seen.get();
    }

    @Test
    void theTokenInTheAuthorizationHeaderAuthenticatesWithoutSessionOrCookie() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/tenants");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication a = authenticate(request, response);
        assertNotNull(a, "the control: this token is valid");
        assertEquals("[ROLE_ADMIN]", a.getAuthorities().toString());
        assertNull(request.getSession(false), "no session is created");
        assertNull(response.getHeader("Set-Cookie"), "no cookie is set");
        assertEquals(0, response.getCookies().length);
    }

    @Test
    void theSameTokenInACookieAuthenticatesNobody() throws Exception {
        for (String name : List.of("JSESSIONID", "SESSION", "access_token", "Authorization", "token")) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/tenants");
            request.setCookies(new Cookie(name, TOKEN));
            request.addHeader("Cookie", name + "=" + TOKEN);
            assertNull(authenticate(request, new MockHttpServletResponse()), "cookie " + name);
            assertNull(request.getSession(false));
        }
    }

    @Test
    void theSameTokenInTheQueryOrAFormFieldAuthenticatesNobody() throws Exception {
        MockHttpServletRequest query = new MockHttpServletRequest("GET", "/api/v1/cells");
        query.setQueryString("access_token=" + TOKEN);
        query.setParameter("access_token", TOKEN);
        assertNull(authenticate(query, new MockHttpServletResponse()), "?access_token");

        MockHttpServletRequest form = new MockHttpServletRequest("POST", "/api/v1/admin/tenants");
        form.setContentType("application/x-www-form-urlencoded");
        form.setContent(("access_token=" + TOKEN).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        form.setParameter("access_token", TOKEN);
        form.setParameter("Authorization", "Bearer " + TOKEN);
        assertNull(authenticate(form, new MockHttpServletResponse()), "form field access_token");

        MockHttpServletRequest basic = new MockHttpServletRequest("GET", "/api/v1/cells");
        basic.addHeader("Authorization", "Basic " + java.util.Base64.getEncoder()
                .encodeToString(("admin:" + TOKEN).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertNull(authenticate(basic, new MockHttpServletResponse()), "a browser-cached Basic login is not a credential");
    }
}
