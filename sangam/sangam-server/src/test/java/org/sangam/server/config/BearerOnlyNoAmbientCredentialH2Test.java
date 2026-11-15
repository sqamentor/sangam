// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.Test;
import org.sangam.server.acceptance.AcceptanceIdp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The precondition under which the server runs without CSRF protection ({@code SecurityConfig}: "bearer tokens only"),
 * tested on the real server (h2, the OIDC decoder and the dev token both on). The code-scanning CSRF alerts on the
 * state-changing routes and on the disabled CSRF filter are answered by these facts, and a later cookie or session
 * login makes them fail:
 * <ol>
 *   <li>a valid token sent only in a cookie authenticates nobody (401);</li>
 *   <li>a valid token sent only as {@code access_token} in the query or in a form body authenticates nobody (401);</li>
 *   <li>an authenticated answer sets no cookie and creates no session;</li>
 *   <li>a CORS answer for an allowed origin never allows credentials;</li>
 *   <li>every handler names its HTTP methods (no handler answers a method it did not declare), except the error
 *       page.</li>
 * </ol>
 * The source side (no cookie, session or browser login in any module's main code) is
 * {@code BearerOnlyAuthenticationGuardTest} in sangam-core.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class BearerOnlyNoAmbientCredentialH2Test {

    static final String ORIGIN = "https://ui.example.org";
    static final String PROTECTED = "/api/v1/me";
    /** No cookie handler and no redirects: the client sends exactly the headers a test sets. */
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> AcceptanceIdp.get().issuer());
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> AcceptanceIdp.get().jwksUri());
        r.add("sangam.security.cors.allowed-origins[0]", () -> ORIGIN);
    }

    @LocalServerPort
    int port;
    @Autowired
    WebApplicationContext context;
    @Autowired
    Environment env;

    /** Both kinds of credential this server accepts in the Authorization header: an OIDC JWT and the dev token. */
    Map<String, String> validTokens() {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("OIDC JWT", AcceptanceIdp.get().token("dev", "csrf-precondition-user",
                List.of("sangam-admin", "sangam-user", "sangam-operator")));
        t.put("dev token", env.getRequiredProperty("sangam.security.dev-token.token"));
        return t;
    }

    HttpResponse<String> send(String method, String pathAndQuery, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .timeout(Duration.ofSeconds(60))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static void assertNoCookie(HttpResponse<String> r, String what) {
        assertEquals(List.of(), r.headers().allValues("set-cookie"), what + ": no cookie is ever set");
    }

    @Test
    void theTokensAreValidInTheAuthorizationHeader() throws Exception {
        for (Map.Entry<String, String> t : validTokens().entrySet()) {
            HttpResponse<String> r = send("GET", PROTECTED, null, "Authorization", "Bearer " + t.getValue());
            assertEquals(200, r.statusCode(), t.getKey() + " (the control for every 401 below): " + r.body());
        }
    }

    @Test
    void aValidTokenInACookieAuthenticatesNobody() throws Exception {
        for (Map.Entry<String, String> t : validTokens().entrySet()) {
            String token = t.getValue();
            List<String> cookies = new ArrayList<>();
            for (String name : List.of("JSESSIONID", "SESSION", "access_token", "Authorization", "token", "id_token")) {
                cookies.add(name + "=" + token);
                cookies.add(name + "=" + enc("Bearer " + token));
            }
            cookies.add("JSESSIONID=" + token + "; access_token=" + token + "; Authorization=" + token);
            for (String cookie : cookies) {
                for (String method : List.of("GET", "POST", "DELETE")) {
                    HttpResponse<String> r = send(method, PROTECTED, null, "Cookie", cookie);
                    String what = t.getKey() + " as cookie " + cookie.substring(0, cookie.indexOf('=')) + ", " + method;
                    assertEquals(401, r.statusCode(), what + ": " + r.body());
                    assertNoCookie(r, what);
                }
            }
        }
    }

    @Test
    void aValidTokenInTheQueryOrAFormBodyAuthenticatesNobody() throws Exception {
        for (Map.Entry<String, String> t : validTokens().entrySet()) {
            String token = enc(t.getValue());
            for (String method : List.of("GET", "POST")) {
                HttpResponse<String> query = send(method, PROTECTED + "?access_token=" + token, null);
                assertEquals(401, query.statusCode(), t.getKey() + " as ?access_token, " + method + ": " + query.body());
                HttpResponse<String> bearerQuery = send(method, PROTECTED + "?Authorization=" + enc("Bearer ") + token, null);
                assertEquals(401, bearerQuery.statusCode(), t.getKey() + " as ?Authorization, " + method);
            }
            for (String method : List.of("POST", "PUT")) {
                HttpResponse<String> form = send(method, PROTECTED, "access_token=" + token,
                        "Content-Type", "application/x-www-form-urlencoded");
                assertEquals(401, form.statusCode(), t.getKey() + " as form field access_token, " + method + ": " + form.body());
                assertNoCookie(form, t.getKey() + " as form field");
            }
        }
    }

    @Test
    void anAuthenticatedAnswerSetsNoCookieAndCreatesNoSession() throws Exception {
        for (Map.Entry<String, String> t : validTokens().entrySet()) {
            HttpResponse<String> r = send("GET", PROTECTED, null, "Authorization", "Bearer " + t.getValue());
            assertEquals(200, r.statusCode(), t.getKey() + ": " + r.body());
            assertNoCookie(r, t.getKey());
        }
        assertNoCookie(send("GET", PROTECTED, null), "an unauthenticated 401");

        // the same security filter chain in front of the same dispatcher, where the session of a request can be seen
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        for (Map.Entry<String, String> t : validTokens().entrySet()) {
            MvcResult authenticated = mvc.perform(get(PROTECTED).header("Authorization", "Bearer " + t.getValue())).andReturn();
            assertNotEquals(401, authenticated.getResponse().getStatus(), t.getKey() + ": the request is authenticated");
            assertNull(authenticated.getRequest().getSession(false), t.getKey() + ": no session is created");
            assertNull(authenticated.getResponse().getHeader("Set-Cookie"), t.getKey());
        }
        MvcResult anonymous = mvc.perform(get(PROTECTED)).andReturn();
        assertEquals(401, anonymous.getResponse().getStatus());
        assertNull(anonymous.getRequest().getSession(false), "no session for a refused request either");
    }

    @Test
    void aCorsAnswerForAnAllowedOriginNeverAllowsCredentials() throws Exception {
        HttpResponse<String> preflight = send("OPTIONS", PROTECTED, null, "Origin", ORIGIN,
                "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "authorization,content-type");
        assertEquals(200, preflight.statusCode(), preflight.body());
        assertEquals(List.of(ORIGIN), preflight.headers().allValues("access-control-allow-origin"), "the origin is allowed");
        assertEquals(List.of(), preflight.headers().allValues("access-control-allow-credentials"),
                "a preflight never allows credentials");
        assertNoCookie(preflight, "preflight");

        String token = validTokens().get("OIDC JWT");
        HttpResponse<String> actual = send("GET", PROTECTED, null, "Origin", ORIGIN, "Authorization", "Bearer " + token);
        assertEquals(200, actual.statusCode(), actual.body());
        assertEquals(List.of(ORIGIN), actual.headers().allValues("access-control-allow-origin"));
        assertEquals(List.of(), actual.headers().allValues("access-control-allow-credentials"),
                "an answer to an allowed origin never allows credentials");

        HttpResponse<String> foreign = send("OPTIONS", PROTECTED, null, "Origin", "https://evil.example",
                "Access-Control-Request-Method", "POST");
        assertEquals(List.of(), foreign.headers().allValues("access-control-allow-origin"), "another origin is not allowed");
        assertEquals(List.of(), foreign.headers().allValues("access-control-allow-credentials"));
    }

    @Test
    void everyHandlerNamesItsHttpMethodsExceptTheErrorPage() {
        String errorPath = env.getProperty("server.error.path", env.getProperty("error.path", "/error"));
        List<String> open = new ArrayList<>();
        int handlers = 0;
        for (RequestMappingHandlerMapping mapping : context.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
            for (Map.Entry<RequestMappingInfo, ?> e : mapping.getHandlerMethods().entrySet()) {
                handlers++;
                RequestMappingInfo info = e.getKey();
                Set<String> patterns = info.getPatternValues();
                if (info.getMethodsCondition().getMethods().isEmpty() && !patterns.equals(Set.of(errorPath))) {
                    open.add(patterns + " -> " + e.getValue());
                }
            }
        }
        assertTrue(handlers > 100, "the server's handlers were inspected (" + handlers + ")");
        assertEquals(List.of(), open, "a handler without explicit HTTP methods answers every method, also a state change"
                + " through GET; name the methods");
    }
}
