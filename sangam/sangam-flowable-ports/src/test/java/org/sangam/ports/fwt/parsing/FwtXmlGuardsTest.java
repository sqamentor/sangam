// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.fwt.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.ports.bpmn.SangamModelValidator;
import org.sangam.ports.modelimport.FlowableModelImporter;
import org.sangam.ports.support.PortsProblem;
import org.sangam.ports.support.SecureXml;

/**
 * FW-01 scenarios (FLOWABLE-WEAKNESS-AUDIT §5): XXE, billion laughs, size and depth limits on every model entry point
 * of the ports module that parses XML: the importer, the pre-deploy validator and the hardened parser that dynamic
 * injection uses for fragments and host models ({@link SecureXml#parse}).
 */
class FwtXmlGuardsTest {

    @TempDir
    Path tmp;

    HttpServer server;
    final AtomicInteger hits = new AtomicInteger();
    Path secret;
    static final String MARKER = "SECRET-MARKER-7f3a";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            byte[] b = ("<!ENTITY x '" + MARKER + "'>").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        secret = tmp.resolve("secret.txt");
        Files.writeString(secret, MARKER);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String httpUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/dtd";
    }

    private String xxe(String encoding) {
        return "<?xml version=\"1.0\" encoding=\"" + encoding + "\"?>\n"
                + "<!DOCTYPE definitions [\n"
                + "  <!ENTITY file SYSTEM \"" + secret.toUri() + "\">\n"
                + "  <!ENTITY % remote SYSTEM \"" + httpUrl() + "\"> %remote;\n"
                + "]>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"t\">"
                + "<process id=\"p\" name=\"&file;\" isExecutable=\"true\"><startEvent id=\"s\"/></process></definitions>";
    }

    private static final String SMALL_OK = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"t\">"
            + "<process id=\"p\" isExecutable=\"true\"><startEvent id=\"s\"/></process></definitions>";

    /** Asserts that every XML entry point refuses {@code xml}; returns the importer's exception. */
    private void allRefuse(byte[] xml, String validatorCode) {
        RuntimeException imp = assertThrows(RuntimeException.class, () -> FlowableModelImporter.convert(xml));
        assertInstanceOf(IllegalArgumentException.class, imp);
        RuntimeException parse = assertThrows(RuntimeException.class, () -> SecureXml.parse(xml));
        assertInstanceOf(IllegalArgumentException.class, parse);
        List<SangamModelValidator.Finding> f = new SangamModelValidator(new SangamModelValidator.Options(true)).validate("m.bpmn", xml);
        assertEquals(1, f.size(), String.valueOf(f));
        assertEquals(SangamModelValidator.Severity.ERROR, f.get(0).severity());
        if (validatorCode != null) {
            assertEquals(validatorCode, f.get(0).code(), String.valueOf(f));
        }
    }

    // FWT-001
    @Test
    void fwt001_doctypeWithExternalEntitiesRejectedUtf8AndUtf16_noFileOrNetworkAccess() {
        byte[] utf8 = xxe("UTF-8").getBytes(StandardCharsets.UTF_8);
        RuntimeException e = assertThrows(IllegalArgumentException.class, () -> FlowableModelImporter.convert(utf8));
        assertTrue(e.getMessage().contains("D-51"), e.getMessage());
        allRefuse(utf8, "D-51");

        // UTF-16: the 4 KiB text pre-check cannot see "<!DOCTYPE"; the parser feature disallow-doctype-decl must
        byte[] utf16 = xxe("UTF-16").getBytes(StandardCharsets.UTF_16);
        assertFalse(SecureXml.hasDoctype(utf16), "precondition: the UTF-8 pre-check is blind to UTF-16");
        RuntimeException e16 = assertThrows(IllegalArgumentException.class, () -> SecureXml.parse(utf16));
        assertTrue(e16.getMessage().toUpperCase().contains("DOCTYPE"), e16.getMessage());
        allRefuse(utf16, null);

        assertEquals(0, hits.get(), "no network access to the external DTD");
        for (Throwable t = e16; t != null; t = t.getCause()) {
            assertFalse(String.valueOf(t.getMessage()).contains(MARKER), "file content leaked: " + t.getMessage());
        }
        assertFalse(e.getMessage().contains(MARKER));
    }

    // FWT-002
    @Test
    void fwt002_billionLaughsAfter4KiBCommentPaddingRejectedByParserFeature() {
        String lol = "<!ENTITY lol \"lol\">" + java.util.stream.IntStream.rangeClosed(1, 9)
                .mapToObj(i -> "<!ENTITY lol" + i + " \"" + ("&lol" + (i == 1 ? "" : String.valueOf(i - 1)) + ";").repeat(10) + "\">")
                .reduce("", String::concat);
        String xml = "<?xml version=\"1.0\"?>\n<!--" + "x".repeat(5000) + "-->\n<!DOCTYPE lolz [" + lol + "]>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"><process id=\"p\" name=\"&lol9;\"/></definitions>";
        byte[] b = xml.getBytes(StandardCharsets.UTF_8);
        assertFalse(SecureXml.hasDoctype(b), "precondition: DOCTYPE is beyond the 4 KiB pre-check");
        RuntimeException e = assertThrows(IllegalArgumentException.class, () -> SecureXml.parse(b));
        assertTrue(e.getMessage().toUpperCase().contains("DOCTYPE"), "parser feature disallow-doctype-decl: " + e.getMessage());
        allRefuse(b, null);
    }

    // FWT-003
    @Test
    void fwt003_elevenMiBModelIsModelTooLarge413() {
        String head = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"t\"><process id=\"p\"><!--";
        String tail = "--></process></definitions>";
        byte[] big = (head + "x".repeat(11 * 1024 * 1024) + tail).getBytes(StandardCharsets.UTF_8);
        for (Runnable r : List.<Runnable>of(() -> FlowableModelImporter.convert(big), () -> SecureXml.parse(big))) {
            PortsProblem p = assertThrows(PortsProblem.class, r::run);
            assertEquals("MODEL_TOO_LARGE", p.code());
            assertEquals(413, p.status());
        }
        List<SangamModelValidator.Finding> f = new SangamModelValidator(new SangamModelValidator.Options(true)).validate("m.bpmn", big);
        assertEquals(1, f.size());
        assertEquals("MODEL_TOO_LARGE", f.get(0).code());
        assertEquals(SangamModelValidator.Severity.ERROR, f.get(0).severity());
    }

    // FWT-004
    @Test
    void fwt004_bpmnNested300DeepRejectedByDepthLimit() {
        StringBuilder sb = new StringBuilder("<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"t\">"
                + "<process id=\"p\" isExecutable=\"true\">");
        for (int i = 0; i < 300; i++) {
            sb.append("<subProcess id=\"s").append(i).append("\">");
        }
        for (int i = 0; i < 300; i++) {
            sb.append("</subProcess>");
        }
        sb.append("</process></definitions>");
        byte[] deep = sb.toString().getBytes(StandardCharsets.UTF_8);
        RuntimeException e = assertThrows(IllegalArgumentException.class, () -> SecureXml.parse(deep));
        assertTrue(e.getMessage().contains("maxElementDepth") || e.getMessage().contains("depth"), e.getMessage());
        allRefuse(deep, "XML");
        // control: a normal model parses
        SecureXml.parse(SMALL_OK.getBytes(StandardCharsets.UTF_8));
    }
}
