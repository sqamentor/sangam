// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The nginx configurations of the UI build (docker/ui/default.conf.template, presentation/sangam-path.conf.template).
 * <p>
 * Owner decisions 120, 121: the approved Noto TTFs are served by the UI image as font/ttf (e2e shell.e2e.ts "fonts").
 * The stock nginx mime.types has no ttf entry, so without an explicit map nginx answered application/octet-stream
 * (ui.yml run 44124788893). Both nginx configurations of the UI build must map ttf in their fonts location, the same
 * type the Vite dev server sends (scripts/deploy-fonts.mjs contentType), and must never fall back to index.html there.
 * <p>
 * The API proxy passes the browser's Host with its port ($http_host). With $host the port was dropped: the server saw
 * http://127.0.0.1 while the UI's writes carried Origin http://127.0.0.1:8080, and with CORS origins configured
 * (sangam.security.cors.allowed-origins) Spring refused the UI's own requests as cross-origin (403, ui.yml run
 * 44135232064: every grievance, application, claim and completion from the browser).
 */
class UiNginxConfTest {

    private static final Path DEPLOY = Path.of("..", "deploy");

    private static String read(Path p) throws IOException {
        return Files.readString(p).replace("\r\n", "\n");
    }

    /** The body of {@code location ^~ <prefix> { ... }}, nested braces included. */
    private static String location(String conf, String prefix) {
        String head = "location ^~ " + prefix + " {";
        int start = conf.indexOf(head);
        assertTrue(start >= 0, "no '" + head + "' block");
        int depth = 0;
        for (int i = start + head.length() - 1; i < conf.length(); i++) {
            char c = conf.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return conf.substring(start + head.length(), i);
        }
        throw new AssertionError("unbalanced '" + head + "' block");
    }

    private static void assertServesFonts(String block) {
        Matcher types = Pattern.compile("types\\s*\\{([^}]*)}").matcher(block);
        assertTrue(types.find(), "a types map in the fonts location");
        String map = types.group(1);
        assertTrue(Pattern.compile("(?m)^\\s*font/ttf\\s+ttf\\s*;").matcher(map).find(), "ttf is font/ttf");
        assertTrue(Pattern.compile("(?m)^\\s*text/plain\\s+txt\\s*;").matcher(map).find(), "the OFL texts stay text/plain");
        assertTrue(block.contains("try_files $uri =404;"), "a missing font is a 404, not the single-page index");
        assertTrue(block.contains("include /etc/nginx/conf.d/security-headers.inc;"), "the security headers apply");
    }

    @Test
    void theUiImageServesTtfAsFontTtf() throws IOException {
        assertServesFonts(location(read(DEPLOY.resolve("docker/ui/default.conf.template")), "/fonts/"));
    }

    @Test
    void thePresentationPathServesTtfAsFontTtf() throws IOException {
        String block = location(read(DEPLOY.resolve("presentation/sangam-path.conf.template")), "/sangam/fonts/");
        assertTrue(block.contains("alias /usr/share/nginx/html/fonts/;"), "the build's fonts folder");
        assertServesFonts(block);
    }

    @Test
    void theDevServerSendsTheSameType() throws IOException {
        String script = read(Path.of("..", "sangam-ui", "scripts", "deploy-fonts.mjs"));
        Matcher m = Pattern.compile("name\\.endsWith\\('\\.ttf'\\) \\? '([^']+)'").matcher(script);
        assertTrue(m.find(), "deploy-fonts.mjs contentType");
        assertEquals("font/ttf", m.group(1));
    }

    /** The API location's {@code proxy_set_header Host} value. */
    private static String proxiedHost(String conf) {
        Matcher m = Pattern.compile("(?m)^\\s*proxy_set_header\\s+Host\\s+(\\S+);").matcher(conf);
        assertTrue(m.find(), "the API proxy sets Host");
        String host = m.group(1);
        assertTrue(!m.find(), "one Host header only");
        return host;
    }

    @Test
    void theUiImagePassesTheHostWithItsPort() throws IOException {
        assertEquals("$http_host", proxiedHost(read(DEPLOY.resolve("docker/ui/default.conf.template"))));
    }

    @Test
    void thePresentationPathPassesTheHostWithItsPort() throws IOException {
        assertEquals("$http_host", proxiedHost(read(DEPLOY.resolve("presentation/sangam-path.conf.template"))));
    }

    /** The directives directly in the {@code server { ... }} block: every nested block (location, types) removed. */
    private static String serverLevel(String conf) {
        Matcher head = Pattern.compile("(?m)^server\\s*\\{").matcher(conf);
        assertTrue(head.find(), "a server block");
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (int i = head.end() - 1; i < conf.length(); i++) {
            char c = conf.charAt(i);
            if (c == '{') {
                depth++;
                continue;
            }
            if (c == '}' && --depth == 0) {
                return out.toString();
            }
            if (depth == 1 && c != '}') {
                out.append(c);
            }
        }
        throw new AssertionError("unbalanced server block");
    }

    /**
     * EV-M-0969: nginx's defaults (absolute_redirect on, port_in_redirect on) turn a redirect into an absolute URL with
     * the container's own port, so behind the host's proxy GET demo.ifms.dev/sangam went to http://demo.ifms.dev:8080/sangam/.
     * With absolute_redirect off at server level every redirect nginx makes (an explicit return 301 and its own
     * directory redirects, /assets -> /assets/) carries the relative Location the browser resolves against the address
     * it used. ui.yml (e2e job) checks the same on the running fronts.
     */
    private static void assertRelativeRedirects(String conf) {
        String server = serverLevel(conf);
        assertTrue(Pattern.compile("(?m)^\\s*absolute_redirect\\s+off\\s*;").matcher(server).find(),
                "'absolute_redirect off;' directly in the server block");
        assertTrue(!Pattern.compile("absolute_redirect\\s+on|port_in_redirect\\s+on|server_name_in_redirect\\s+on").matcher(conf).find(),
                "no directive switches absolute redirects back on");
        Matcher r = Pattern.compile("return\\s+(30[1278])\\s+(\\S+?);").matcher(conf);
        while (r.find()) {
            assertTrue(r.group(2).startsWith("/") && !r.group(2).startsWith("//"),
                    "redirect " + r.group(0) + " names a path, not a scheme, host or port");
        }
    }

    @Test
    void theUiImageRedirectsWithARelativeLocation() throws IOException {
        assertRelativeRedirects(read(DEPLOY.resolve("docker/ui/default.conf.template")));
    }

    @Test
    void thePresentationPathRedirectsWithARelativeLocation() throws IOException {
        String conf = read(DEPLOY.resolve("presentation/sangam-path.conf.template"));
        assertTrue(conf.contains("location = /sangam { return 301 /sangam/; }"), "GET /sangam answers 301 to /sangam/");
        assertRelativeRedirects(conf);
    }
}
