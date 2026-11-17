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
 * Owner decisions 120, 121: the approved Noto TTFs are served by the UI image as font/ttf (e2e shell.e2e.ts "fonts").
 * The stock nginx mime.types has no ttf entry, so without an explicit map nginx answered application/octet-stream
 * (ui.yml run 44124788893). Both nginx configurations of the UI build must map ttf in their fonts location, the same
 * type the Vite dev server sends (scripts/deploy-fonts.mjs contentType), and must never fall back to index.html there.
 */
class UiNginxFontTypesTest {

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
}
