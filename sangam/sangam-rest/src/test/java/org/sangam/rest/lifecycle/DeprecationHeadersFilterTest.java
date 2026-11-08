// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** SL-38: registry validation and the Deprecation / Sunset / Link headers. */
class DeprecationHeadersFilterTest {

    private static final String REGISTRY = """
            policy: {minimumSupportDays: 365}
            deprecations:
              - method: GET
                path: /api/v1/cases/{caseId}
                deprecated: 2026-01-01
                sunset: 2027-01-01
                link: https://sangam.dev/changelog/api#cases-v1
                successor: /api/v1/cases/{caseId}/view
              - method: GET
                path: /cases/search
                deprecated: 2026-01-01
                sunset: 2027-03-01
                link: https://sangam.dev/changelog/api#search
            """;

    @Test
    void deprecatedOperationCarriesHeadersAndStillRuns() throws Exception {
        Result r = run(REGISTRY, "GET", "/api/v1/cases/42", Instant.parse("2026-11-08T10:00:00Z"));
        assertTrue(r.chainRan.get(), "a deprecated operation still works before its sunset");
        assertEquals("@" + Instant.parse("2026-01-01T00:00:00Z").getEpochSecond(), r.headers.get("Deprecation").get(0));
        assertEquals("Fri, 01 Jan 2027 00:00:00 GMT", r.headers.get("Sunset").get(0));
        assertEquals(List.of("<https://sangam.dev/changelog/api#cases-v1>; rel=\"deprecation\"",
                "</api/v1/cases/{caseId}/view>; rel=\"successor-version\""), r.headers.get("Link"));
    }

    @Test
    void literalTemplateWinsOverVariableTemplate() throws Exception {
        Result r = run(REGISTRY, "GET", "/api/v1/cases/search", Instant.parse("2026-11-08T10:00:00Z"));
        assertEquals("Mon, 01 Mar 2027 00:00:00 GMT", r.headers.get("Sunset").get(0));
    }

    @Test
    void operationIsGoneFromItsSunsetDate() throws Exception {
        Result r = run(REGISTRY, "GET", "/api/v1/cases/42", Instant.parse("2027-01-01T00:00:00Z"));
        assertFalse(r.chainRan.get(), "a retired operation must not reach the controller");
        assertEquals(410, r.status.get());
        String body = r.body.toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"code\":\"API_SUNSET\"") && body.contains("\"successor\":\"/api/v1/cases/{caseId}/view\""),
                body);
        assertEquals("Fri, 01 Jan 2027 00:00:00 GMT", r.headers.get("Sunset").get(0));
    }

    @Test
    void otherOperationsAreUntouched() throws Exception {
        Result post = run(REGISTRY, "POST", "/api/v1/cases/42", Instant.parse("2027-06-01T00:00:00Z"));
        Result deeper = run(REGISTRY, "GET", "/api/v1/cases/42/tasks", Instant.parse("2027-06-01T00:00:00Z"));
        for (Result r : List.of(post, deeper)) {
            assertTrue(r.chainRan.get());
            assertNull(r.headers.get("Deprecation"));
            assertNull(r.headers.get("Sunset"));
        }
    }

    @Test
    void registryRejectsAShortSupportWindow() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> load("""
                deprecations:
                  - {method: GET, path: /cases, deprecated: 2026-01-01, sunset: 2026-06-01, link: https://x}
                """));
        assertTrue(e.getMessage().contains("minimum support window is 365 days"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> load("policy: {minimumSupportDays: 30}\ndeprecations: []\n"),
                "a registry may raise the window but never lower it below the written policy");
    }

    @Test
    void registryRejectsMissingLinkUnknownMethodAndDuplicates() {
        assertThrows(IllegalStateException.class, () -> load("""
                deprecations:
                  - {method: GET, path: /cases, deprecated: 2026-01-01, sunset: 2027-06-01}
                """));
        assertThrows(IllegalStateException.class, () -> load("""
                deprecations:
                  - {method: FETCH, path: /cases, deprecated: 2026-01-01, sunset: 2027-06-01, link: https://x}
                """));
        assertThrows(IllegalStateException.class, () -> load("""
                deprecations:
                  - {method: GET, path: /cases, deprecated: 2026-01-01, sunset: 2027-06-01, link: https://x}
                  - {method: get, path: /api/v1/cases, deprecated: 2026-02-01, sunset: 2027-06-01, link: https://y}
                """));
    }

    /** Every packaged entry names an operation of the generated contract (the generator asserts the same). */
    @Test
    @SuppressWarnings("unchecked")
    void packagedRegistryIsValidAndNamesExistingOperations() throws IOException {
        ApiDeprecationRegistry reg;
        try (InputStream in = Files.newInputStream(Path.of("api", ApiDeprecationRegistry.RESOURCE))) {
            reg = ApiDeprecationRegistry.load(in);
        }
        assertTrue(reg.minimumSupportDays() >= ApiDeprecationRegistry.FLOOR_SUPPORT_DAYS);
        LoaderOptions o = new LoaderOptions();
        o.setCodePointLimit(64 * 1024 * 1024);
        Map<String, Object> spec;
        try (InputStream in = Files.newInputStream(Path.of("api", "sangam-api.yaml"))) {
            spec = new Yaml(new SafeConstructor(o)).load(in);
        }
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        for (ApiDeprecationRegistry.Entry e : reg.entries()) {
            Map<String, Object> ops = (Map<String, Object>) paths.get(e.path());
            boolean retired = !java.time.LocalDate.now(ZoneOffset.UTC).isBefore(e.sunset());
            assertTrue(retired || ops != null && ops.containsKey(e.method().toLowerCase()),
                    "registry entry without operation: " + e);
            if (ops != null && ops.get(e.method().toLowerCase()) instanceof Map<?, ?> op) {
                assertEquals(Boolean.TRUE, op.get("deprecated"), "spec must mark " + e + " deprecated");
            }
        }
    }

    // ---------------------------------------------------------------- servlet fakes

    private static ApiDeprecationRegistry load(String yaml) {
        return ApiDeprecationRegistry.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    record Result(AtomicBoolean chainRan, AtomicInteger status, Map<String, List<String>> headers,
                  ByteArrayOutputStream body) {
    }

    private static Result run(String registry, String method, String uri, Instant now) throws Exception {
        DeprecationHeadersFilter f = new DeprecationHeadersFilter(load(registry), Clock.fixed(now, ZoneOffset.UTC));
        Map<String, Object> attrs = new LinkedHashMap<>();
        HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(DeprecationHeadersFilterTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (p, m, a) -> switch (m.getName()) {
                    case "getMethod" -> method;
                    case "getRequestURI" -> uri;
                    case "getContextPath", "getServletPath" -> "";
                    case "getAttribute" -> attrs.get((String) a[0]);
                    case "setAttribute" -> attrs.put((String) a[0], a[1]);
                    case "removeAttribute" -> attrs.remove((String) a[0]);
                    case "getDispatcherType" -> jakarta.servlet.DispatcherType.REQUEST;
                    default -> m.getReturnType() == boolean.class ? (Object) false
                            : m.getReturnType() == int.class ? (Object) 0 : null;
                });
        Result r = new Result(new AtomicBoolean(), new AtomicInteger(200), new LinkedHashMap<>(), new ByteArrayOutputStream());
        ServletOutputStream out = new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener l) {
            }

            @Override
            public void write(int b) {
                r.body.write(b);
            }
        };
        HttpServletResponse res = (HttpServletResponse) Proxy.newProxyInstance(DeprecationHeadersFilterTest.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "setHeader" -> r.headers.put((String) a[0], new ArrayList<>(List.of((String) a[1])));
                        case "addHeader" -> r.headers.computeIfAbsent((String) a[0], k -> new ArrayList<>()).add((String) a[1]);
                        case "setStatus" -> r.status.set((Integer) a[0]);
                        case "getOutputStream" -> {
                            return out;
                        }
                        default -> {
                        }
                    }
                    return null;
                });
        FilterChain chain = (q, s) -> r.chainRan.set(true);
        f.doFilter(req, res, chain);
        return r;
    }
}
