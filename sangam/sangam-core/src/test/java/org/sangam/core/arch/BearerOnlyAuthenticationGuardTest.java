// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Source guard for the precondition under which CSRF protection is off in every Sangam application (code scanning
 * java/spring-disabled-csrf-protection and the CSRF alerts on the state-changing routes): <b>a request is authenticated
 * only by a credential the client attaches on purpose</b>, the {@code Authorization: Bearer} header. A browser attaches
 * no such header to a cross-site request, so there is nothing for a forged request to ride on. That stops being true
 * the day a credential becomes ambient (a session cookie, a cookie that carries a token, a browser-cached login), and
 * that day this test fails, before the CSRF decision is silently wrong.
 *
 * <p>A plain-Java scan of every module's {@code src/main} Java source, in the style of {@link ArchitectureGuardTest}
 * (same reactor walk, comment lines skipped). It fails on:
 * <ol>
 *   <li>reading a cookie from a request ({@code getCookies(}, {@code @CookieValue}, the {@code Cookie} request header);</li>
 *   <li>setting a cookie on a response ({@code addCookie(}, {@code ResponseCookie});</li>
 *   <li>a server-side session ({@code HttpSession}, {@code WebSession});</li>
 *   <li>a browser login that is kept by the browser ({@code formLogin(}, {@code httpBasic(}, {@code oauth2Login(},
 *       {@code saml2Login(}, {@code rememberMe(}, {@code oneTimeTokenLogin(}), unless the call switches it off;</li>
 *   <li>a bearer token accepted from the query or a form body ({@code setAllowUriQueryParameter(true)},
 *       {@code setAllowFormEncodedBodyParameter(true)}), or CORS with credentials ({@code setAllowCredentials(true)});</li>
 *   <li>a session policy other than {@code SessionCreationPolicy.STATELESS}, and a source that configures
 *       {@code HttpSecurity} without setting that policy.</li>
 * </ol>
 * A legitimate use that is not authentication of a Sangam caller (for example a connector that keeps a third party's
 * session) goes on {@link #ALLOWED} with its reason and exact line count; an entry that no longer matches fails, so
 * the list cannot go stale. The run-time side is {@code BearerOnlyNoAmbientCredentialH2Test} in sangam-server.
 */
class BearerOnlyAuthenticationGuardTest {

    /** A browser login mechanism; group 1 is its configurer method. */
    private static final String LOGIN = "(formLogin|httpBasic|oauth2Login|saml2Login|rememberMe|oneTimeTokenLogin)";

    /** The same call switching the mechanism off: {@code formLogin(f -> f.disable())}, {@code httpBasic(X::disable)}. */
    static final Pattern LOGIN_DISABLED = Pattern.compile("\\b" + LOGIN
            + "\\s*\\(\\s*(?:\\w+\\s*->\\s*\\w+\\s*\\.\\s*disable\\s*\\(\\s*\\)|[\\w.]+\\s*::\\s*disable)\\s*\\)");

    static final Pattern LOGIN_CALL = Pattern.compile("\\b" + LOGIN + "\\s*\\(");

    /** Ambient credentials other than a login configurer (items 1, 2, 3 and 5 of the class comment). */
    static final Pattern AMBIENT = Pattern.compile(
            "\\bgetCookies\\s*\\("
                    + "|@CookieValue\\b"
                    + "|\\bgetHeaders?\\s*\\(\\s*\"(?i:cookie)\"\\s*\\)"
                    + "|\\baddCookie\\s*\\("
                    + "|\\bResponseCookie\\b"
                    + "|\\bHttpSession\\b"
                    + "|\\bWebSession\\b"
                    + "|\\bsetAllowUriQueryParameter\\s*\\(\\s*true\\s*\\)"
                    + "|\\bsetAllowFormEncodedBodyParameter\\s*\\(\\s*true\\s*\\)"
                    + "|\\bsetAllowCredentials\\s*\\(\\s*(?:true|Boolean\\.TRUE)\\s*\\)"
                    + "|\\ballowCredentials\\s*\\(\\s*true\\s*\\)");

    static final Pattern SESSION_POLICY = Pattern.compile("\\bsessionCreationPolicy\\s*\\(");
    static final Pattern STATELESS = Pattern.compile(
            "\\bsessionCreationPolicy\\s*\\(\\s*(?:[\\w.]*\\.)?SessionCreationPolicy\\s*\\.\\s*STATELESS\\s*\\)");
    static final Pattern HTTP_SECURITY = Pattern.compile("\\bHttpSecurity\\b");

    /**
     * Main source (reactor-relative, forward slashes) -> exact number of allowed matching lines, each with its reason.
     * Empty on 2026-11-15: no main source matches. The connectors that keep a third party's session send its cookie
     * in an outbound {@code Cookie} header they build themselves (sangam-channels EnterpriseConnectors, sangam-integration
     * ArazzoOperationStep); that is not a credential of a Sangam caller and matches none of the patterns.
     */
    static final Map<String, Integer> ALLOWED = new TreeMap<>();

    /** Every violation of one source: {@code path:line: text}. */
    static List<String> violations(String rel, String text) {
        List<String> out = new ArrayList<>(ArchitectureGuardTest.codeHits(rel, text, AMBIENT));
        for (String hit : ArchitectureGuardTest.codeHits(rel, text, LOGIN_CALL)) {
            // the line may hold several configurer calls: it passes only when each login call on it is a disable
            String line = hit.substring(hit.indexOf(": ") + 2);
            if (count(LOGIN_CALL, line) != count(LOGIN_DISABLED, line)) {
                out.add(hit);
            }
        }
        List<String> policies = ArchitectureGuardTest.codeHits(rel, text, SESSION_POLICY);
        for (String hit : policies) {
            if (!STATELESS.matcher(hit).find()) {
                out.add(hit);
            }
        }
        if (policies.isEmpty() && configuresHttpSecurity(rel, text)) {
            out.add(rel + ": configures HttpSecurity without sessionCreationPolicy(SessionCreationPolicy.STATELESS)");
        }
        return out;
    }

    /** A source that builds a filter chain: it names {@code HttpSecurity} in code and calls a configurer on it. */
    static boolean configuresHttpSecurity(String rel, String text) {
        return !ArchitectureGuardTest.codeHits(rel, text, HTTP_SECURITY).isEmpty()
                && Pattern.compile("\\bauthorizeHttpRequests\\s*\\(|\\bcsrf\\s*\\(|\\.build\\s*\\(\\s*\\)").matcher(text).find()
                && Pattern.compile("\\bSecurityFilterChain\\b").matcher(text).find();
    }

    private static int count(Pattern p, String s) {
        int n = 0;
        for (var m = p.matcher(s); m.find(); ) {
            n++;
        }
        return n;
    }

    @Test
    void noMainSourceAuthenticatesByAnAmbientCredential() throws IOException {
        Path root = ArchitectureGuardTest.root();
        Map<String, Integer> counts = new TreeMap<>();
        List<String> found = new ArrayList<>();
        int scanned = 0;
        int chains = 0;
        for (Path p : ArchitectureGuardTest.files(root)) {
            String r = ArchitectureGuardTest.rel(root, p);
            if (!ArchitectureGuardTest.isMain(r) || !r.endsWith(".java")) {
                continue;
            }
            String text = ArchitectureGuardTest.read(p);
            if (text == null) {
                continue;
            }
            scanned++;
            if (configuresHttpSecurity(r, text)) {
                chains++;
            }
            List<String> v = violations(r, text);
            if (!v.isEmpty()) {
                counts.put(r, v.size());
                if (!Integer.valueOf(v.size()).equals(ALLOWED.get(r))) {
                    found.addAll(v);
                }
            }
        }
        assertTrue(scanned > 500, "scanned the main Java sources (" + scanned + ")");
        assertTrue(chains >= 3, "found the security filter chains of the applications (" + chains + ")");
        assertTrue(found.isEmpty(), "bearer-only authentication (the precondition of CSRF being off): an ambient"
                + " credential needs CSRF protection first, or an ALLOWED entry with the reason it is not one: " + found);
        for (Map.Entry<String, Integer> e : ALLOWED.entrySet()) {
            assertEquals(e.getValue(), counts.getOrDefault(e.getKey(), 0),
                    "allow-list entry is stale or changed (remove or correct it): " + e.getKey());
        }
    }

    @Test
    void patternsMatchWhatTheyBan() {
        for (String s : List.of("Cookie[] c = request.getCookies();", "public String me(@CookieValue(\"sid\") String sid) {",
                "String c = req.getHeader(\"Cookie\");", "req.getHeaders(\"cookie\")", "response.addCookie(cookie);",
                "ResponseCookie.from(\"sid\", v).build()", "HttpSession s = request.getSession();",
                "public Mono<Void> x(WebSession session) {", "resolver.setAllowUriQueryParameter(true);",
                "resolver.setAllowFormEncodedBodyParameter(true);", "cors.setAllowCredentials(true);",
                "cors.setAllowCredentials(Boolean.TRUE);", "registry.addMapping(\"/**\").allowCredentials(true);")) {
            assertEquals(1, violations("A.java", s + "\n").size(), s);
        }
        for (String s : List.of("http.formLogin(Customizer.withDefaults());", "http.formLogin(f -> f.loginPage(\"/login\"));",
                ".httpBasic(Customizer.withDefaults())", ".oauth2Login(o -> o.loginPage(\"/x\"))", "http.saml2Login(s -> { });",
                ".rememberMe(r -> r.key(\"k\"))", ".oneTimeTokenLogin(Customizer.withDefaults())",
                ".formLogin(f -> f.disable()).httpBasic(Customizer.withDefaults())",
                ".sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))",
                "s.sessionCreationPolicy(SessionCreationPolicy.ALWAYS)", "s.sessionCreationPolicy(policy)")) {
            assertEquals(1, violations("A.java", s + "\n").size(), s);
        }
        for (String s : List.of(".formLogin(f -> f.disable())", ".httpBasic(b -> b.disable())",
                "http.csrf(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)",
                ".formLogin(f -> f.disable()).httpBasic(b -> b.disable())",
                ".sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))",
                "s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS)",
                "h.put(\"Cookie\", String.join(\"; \", cookies));", "x.getResponseHeaders().add(\"Set-Cookie\", \"sim=1\");",
                "performLogin(user);", "String sessionId = sftp.getSession().toString();", "HttpSessionless x;",
                "cors.setAllowCredentials(false);", "resolver.setAllowUriQueryParameter(false);",
                "// request.getCookies() in a comment", " * HttpSession in javadoc", "String s = req.getHeader(\"Authorization\");")) {
            assertEquals(List.of(), violations("A.java", s + "\n"), s);
        }
    }

    @Test
    void aChainWithoutAStatelessPolicyIsAViolation() {
        String chain = "class C {\n    SecurityFilterChain chain(HttpSecurity http) throws Exception {\n"
                + "        http.csrf(c -> c.disable()).authorizeHttpRequests(a -> a.anyRequest().denyAll());\n"
                + "        return http.build();\n    }\n}\n";
        assertTrue(configuresHttpSecurity("C.java", chain));
        assertEquals(List.of("C.java: configures HttpSecurity without sessionCreationPolicy(SessionCreationPolicy.STATELESS)"),
                violations("C.java", chain));
        String stateless = chain.replace("http.csrf(", "http.sessionManagement(s -> s.sessionCreationPolicy("
                + "SessionCreationPolicy.STATELESS)).csrf(");
        assertEquals(List.of(), violations("C.java", stateless));
        assertFalse(configuresHttpSecurity("D.java", "class D { void f(HttpSecurity http) { } }\n"),
                "a source that only passes HttpSecurity on configures no chain");
        for (Map.Entry<String, Integer> e : ALLOWED.entrySet()) {
            assertTrue(e.getValue() > 0 && ArchitectureGuardTest.isMain(e.getKey()) && e.getKey().endsWith(".java"),
                    "entries are main Java sources with a positive count: " + e.getKey());
        }
    }
}
