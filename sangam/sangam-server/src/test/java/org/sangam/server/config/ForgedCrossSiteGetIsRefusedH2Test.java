// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Round 9 CSRF, owner ruling Q11 (2026-11-17): every GET handler that CodeQL flags with
 * {@code java/csrf-unprotected-request-type} is dismissed only with a forged-request 401 test for that handler. This
 * is that test, one request per GET route of the real server: a cross-site GET as a browser would send it (a foreign
 * {@code Origin} and {@code Referer}, every ambient credential a browser could hold, a valid token in a cookie and in
 * the query, but no {@code Authorization} header) is refused with 401 before any handler code runs, so no audit row,
 * write or engine command can be caused by a forged request.
 *
 * <p>The only GET routes that answer without a token are listed in {@link #PUBLIC}: each is anonymous by design and
 * is reviewed in CSRF-FOUR-BUCKET-REPORT.md. A new anonymous GET route fails this test until it is reviewed.
 * The per-route evidence is written to {@code target/csrf-forged-get.tsv}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class ForgedCrossSiteGetIsRefusedH2Test {

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /** GET handlers that answer an anonymous request (pattern -> handler), reviewed one by one in the report. */
    static final Set<String> PUBLIC = Set.of(
            // decision 380: the hosted catalogue is public (SecurityConfig.HOSTED_CATALOGUE_PATHS); reads only
            "/api/v1/catalogue/hosted/index.json -> org.sangam.rest.modules.modeller.CatalogueHostController.index",
            "/api/v1/catalogue/hosted/index.json.sig -> org.sangam.rest.modules.modeller.CatalogueHostController.signature",
            "/api/v1/catalogue/hosted/artifacts/{id}/{version} -> org.sangam.rest.modules.modeller.CatalogueHostController.artifact",
            // the API description and UI plugin assets are static and permitAll in SecurityConfig
            "/api/v1/openapi.yaml -> org.sangam.server.web.OpenApiController.openApi",
            "/api/v1/plugins/ui/{id}/assets/{*path} -> org.sangam.server.web.UiPluginController.asset",
            // M-006: CDS Hooks discovery; the CDS client JWT is checked by the controller, the fallback answers 501
            "/cds-services -> org.sangam.rest.modules.connectorshealth.HealthHooksFallbackController.cdsDiscovery");

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> AcceptanceIdp.get().issuer());
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> AcceptanceIdp.get().jwksUri());
    }

    @LocalServerPort
    int port;
    @Autowired
    WebApplicationContext context;
    @Autowired
    Environment env;

    /** A concrete path for a pattern: every {@code {var}} (with or without a regex) becomes a harmless value. */
    static String concrete(String pattern) {
        String p = pattern.replaceAll("\\{\\*[^}]*}", "x").replaceAll("\\{[^}:]*:[^}]*}", "1")
                .replaceAll("\\{[^}]*}", "00000000-0000-0000-0000-000000000001");
        return p.replace("/**", "/x").replace("/*", "/x");
    }

    HttpResponse<String> forgedGet(String path, String token) throws Exception {
        String enc = URLEncoder.encode(token, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path
                        + (path.contains("?") ? "&" : "?") + "access_token=" + enc))
                .timeout(Duration.ofSeconds(60)).GET()
                .header("Origin", "https://evil.example")
                .header("Referer", "https://evil.example/forged.html")
                .header("Sec-Fetch-Site", "cross-site")
                .header("Cookie", "JSESSIONID=" + token + "; SESSION=" + token + "; access_token=" + token
                        + "; Authorization=" + URLEncoder.encode("Bearer " + token, StandardCharsets.UTF_8))
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void everyGetRouteRefusesAForgedCrossSiteRequestWith401() throws Exception {
        String token = AcceptanceIdp.get().token("dev", "csrf-forger",
                List.of("sangam-admin", "sangam-user", "sangam-operator"));
        String devToken = env.getRequiredProperty("sangam.security.dev-token.token");
        String errorPath = env.getProperty("server.error.path", "/error");
        Map<String, String> evidence = new TreeMap<>();
        Set<String> anonymous = new TreeSet<>();
        List<String> wrong = new ArrayList<>();
        int routes = 0;
        for (RequestMappingHandlerMapping mapping : context.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
                RequestMappingInfo info = e.getKey();
                Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                if (!methods.contains(RequestMethod.GET) && !methods.isEmpty()) {
                    continue;
                }
                HandlerMethod h = e.getValue();
                String handler = h.getBeanType().getName() + "." + h.getMethod().getName();
                for (String pattern : info.getPatternValues()) {
                    if (pattern.equals(errorPath)) {
                        continue;
                    }
                    routes++;
                    String path = concrete(pattern);
                    int jwt = forgedGet(path, token).statusCode();
                    int dev = forgedGet(path, devToken).statusCode();
                    evidence.put(handler + "\t" + pattern, jwt + "\t" + dev);
                    if (jwt != 401 || dev != 401) {
                        anonymous.add(pattern + " -> " + handler);
                        if (!PUBLIC.contains(pattern + " -> " + handler)) {
                            wrong.add(pattern + " -> " + handler + " answered " + jwt + "/" + dev);
                        }
                    }
                }
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("handler\tpattern\tforged-with-jwt\tforged-with-dev-token");
        evidence.forEach((k, v) -> lines.add(k + "\t" + v));
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target/csrf-forged-get.tsv"), lines, StandardCharsets.UTF_8);
        assertTrue(routes > 300, "GET routes inspected: " + routes);
        assertEquals(List.of(), wrong, "a forged cross-site GET must be refused with 401 before any handler runs;"
                + " anonymous routes seen: " + anonymous);
        assertEquals(PUBLIC, anonymous, "every listed public GET route is still anonymous");
    }
}
