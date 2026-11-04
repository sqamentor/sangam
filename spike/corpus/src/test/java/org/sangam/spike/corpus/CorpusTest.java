package org.sangam.spike.corpus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class CorpusTest {

    static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    static final String DMN13_NS = "https://www.omg.org/spec/DMN/20191111/MODEL/";
    static final String CMMN11_NS = "http://www.omg.org/spec/CMMN/20151109/MODEL";
    static final String FLOWABLE_NS = "http://flowable.org/bpmn";
    static final String OPERATON_NS = "http://operaton.org/schema/1.0/bpmn";
    static final List<String> VENDOR_NS_MARKERS = List.of(
            "flowable.org", "operaton.org", "camunda.org", "activiti.org");

    // ---------------------------------------------------------------- helpers

    static Path modelsDir() throws Exception {
        URL url = Corpus.class.getClassLoader().getResource("models");
        if (url == null) {
            throw new IllegalStateException("models/ not on the test classpath");
        }
        return Path.of(url.toURI());
    }

    static List<Path> modelFiles() throws Exception {
        try (Stream<Path> s = Files.list(modelsDir())) {
            return s.filter(Files::isRegularFile).sorted().toList();
        }
    }

    static Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        b.setErrorHandler(null);
        return b.parse(new ByteArrayInputStream(bytes));
    }

    /** "wf1-bill.flowable.bpmn" -> {"wf1-bill", "flowable", "bpmn"}; shared -> {"wf2-file", null, "bpmn"}. */
    static String[] split(String fileName) {
        String[] parts = fileName.split("\\.");
        if (parts.length == 3) {
            return parts;
        }
        if (parts.length == 2) {
            return new String[] {parts[0], null, parts[1]};
        }
        throw new IllegalArgumentException("Unexpected model file name " + fileName);
    }

    static Set<String> ids(Document doc) {
        Set<String> ids = new TreeSet<>();
        NodeList all = doc.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (e.hasAttribute("id")) {
                ids.add(e.getLocalName() + "#" + e.getAttribute("id"));
            }
        }
        return ids;
    }

    static Stream<String> allModelFileNames() throws Exception {
        return modelFiles().stream().map(p -> p.getFileName().toString());
    }

    // ---------------------------------------------------------------- tests

    @ParameterizedTest(name = "{0} is well-formed and uses the right standard namespace")
    @MethodSource("allModelFileNames")
    void everyModelIsWellFormedStandardXml(String fileName) throws Exception {
        Document doc = parse(Files.readAllBytes(modelsDir().resolve(fileName)));
        Element root = doc.getDocumentElement();
        assertEquals("definitions", root.getLocalName(), fileName);
        String ext = split(fileName)[2];
        String expectedNs = switch (ext) {
            case "bpmn" -> BPMN_NS;
            case "dmn" -> DMN13_NS;
            case "cmmn" -> CMMN11_NS;
            default -> throw new AssertionError("Unexpected extension in " + fileName);
        };
        assertEquals(expectedNs, root.getNamespaceURI(), fileName);
    }

    @ParameterizedTest(name = "{0}: vendor namespaces match the file kind")
    @MethodSource("allModelFileNames")
    void vendorNamespacesOnlyInTheirOwnVariant(String fileName) throws Exception {
        String text = Files.readString(modelsDir().resolve(fileName), StandardCharsets.UTF_8);
        String engine = split(fileName)[1];
        // Only look at namespace declarations (comments may mention the other engine by name).
        List<String> declared = new ArrayList<>();
        Document doc = parse(text.getBytes(StandardCharsets.UTF_8));
        var attrs = doc.getDocumentElement().getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            var a = attrs.item(i);
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(a.getNamespaceURI())) {
                declared.add(a.getNodeValue());
            }
        }
        for (String ns : declared) {
            boolean vendor = VENDOR_NS_MARKERS.stream().anyMatch(ns::contains);
            if (!vendor) {
                continue;
            }
            if (engine == null) {
                fail(fileName + " is shared but declares vendor namespace " + ns);
            } else if (Corpus.FLOWABLE.equals(engine)) {
                assertEquals(FLOWABLE_NS, ns, fileName + " (flowable variant) declares " + ns);
            } else {
                assertEquals(OPERATON_NS, ns, fileName + " (operaton variant) declares " + ns);
            }
        }
        if (engine != null) {
            String own = Corpus.FLOWABLE.equals(engine) ? FLOWABLE_NS : OPERATON_NS;
            assertTrue(declared.contains(own), fileName + " is a variant but does not declare " + own);
        }
    }

    /** VARIANTS.md F-01: Flowable picks the DMN XSD from the first StAX event, so the root must come first. */
    @Test
    void dmnFilesHaveNothingBeforeTheRootElement() throws Exception {
        for (String f : allModelFileNames().toList()) {
            if (!"dmn".equals(split(f)[2])) {
                continue;
            }
            Document doc = parse(Files.readAllBytes(modelsDir().resolve(f)));
            assertTrue(doc.getFirstChild() instanceof Element,
                    f + ": a comment or processing instruction precedes <definitions> (breaks Flowable DMN version detection)");
        }
    }

    @Test
    void variantsComeInPairsAndHaveNoSharedTwin() throws Exception {
        Set<String> names = new TreeSet<>(allModelFileNames().toList());
        for (String f : names) {
            String[] p = split(f);
            if (p[1] == null) {
                continue;
            }
            assertTrue(Corpus.ENGINES.contains(p[1]), "Unknown engine suffix in " + f);
            for (String engine : Corpus.ENGINES) {
                assertTrue(names.contains(p[0] + "." + engine + "." + p[2]),
                        f + " has no " + engine + " twin");
            }
            assertFalse(names.contains(p[0] + "." + p[2]),
                    f + " is a variant but a shared " + p[0] + "." + p[2] + " also exists (ambiguous)");
        }
    }

    @Test
    void variantPairsDeclareTheSameElementIds() throws Exception {
        for (String f : allModelFileNames().toList()) {
            String[] p = split(f);
            if (!Corpus.OPERATON.equals(p[1])) {
                continue;
            }
            Path dir = modelsDir();
            Set<String> operaton = ids(parse(Files.readAllBytes(dir.resolve(f))));
            Set<String> flowable = ids(parse(Files.readAllBytes(dir.resolve(p[0] + ".flowable." + p[2]))));
            // Element names may differ (e.g. businessRuleTask vs serviceTask); ids must not.
            Set<String> opIds = new TreeSet<>();
            operaton.forEach(s -> opIds.add(s.substring(s.indexOf('#') + 1)));
            Set<String> flIds = new TreeSet<>();
            flowable.forEach(s -> flIds.add(s.substring(s.indexOf('#') + 1)));
            assertEquals(opIds, flIds, "element ids differ between variants of " + p[0]);
        }
    }

    @Test
    void everyVariantFileIsRecordedInVariantsMd() throws Exception {
        Path variantsMd = Path.of("VARIANTS.md");
        assertTrue(Files.exists(variantsMd), "corpus/VARIANTS.md missing (working dir " + Path.of("").toAbsolutePath() + ")");
        String md = Files.readString(variantsMd, StandardCharsets.UTF_8);
        for (String f : allModelFileNames().toList()) {
            if (split(f)[1] != null) {
                assertTrue(md.contains(f), "VARIANTS.md does not mention " + f);
            }
        }
    }

    @Test
    void everyLogicalModelResolvesOnBothEnginesWithMatchingDefinitionId() throws Exception {
        for (String engine : Corpus.ENGINES) {
            for (String name : Corpus.BPMN_MODELS) {
                Document doc = parse(Corpus.bpmn(name, engine));
                assertTrue(hasElementWithId(doc, BPMN_NS, "process", name),
                        "no <process id=\"" + name + "\"> in " + Corpus.resolve(name, "bpmn", engine));
            }
            for (String name : Corpus.DMN_MODELS) {
                Document doc = parse(Corpus.dmn(name, engine));
                assertTrue(hasElementWithId(doc, DMN13_NS, "decision", name), "no decision " + name);
            }
            for (String name : Corpus.CMMN_MODELS) {
                Document doc = parse(Corpus.cmmn(name, engine));
                assertTrue(hasElementWithId(doc, CMMN11_NS, "case", name), "no case " + name);
            }
        }
    }

    @Test
    void everyModelFileIsListedInCorpus() throws Exception {
        Set<String> listed = new TreeSet<>();
        Corpus.BPMN_MODELS.forEach(n -> listed.add(n + ".bpmn"));
        Corpus.DMN_MODELS.forEach(n -> listed.add(n + ".dmn"));
        Corpus.CMMN_MODELS.forEach(n -> listed.add(n + ".cmmn"));
        for (String f : allModelFileNames().toList()) {
            String[] p = split(f);
            assertTrue(listed.contains(p[0] + "." + p[2]), f + " is not listed in Corpus.*_MODELS");
        }
    }

    @Test
    void resolvePrefersTheVariantAndFallsBackToShared() {
        assertEquals("models/wf1-bill.flowable.bpmn", Corpus.resolve("wf1-bill", "bpmn", "flowable"));
        assertEquals("models/wf1-bill.operaton.bpmn", Corpus.resolve("wf1-bill", "bpmn", "Operaton"));
        assertEquals("models/wf1-bill.operaton.bpmn", Corpus.resolve("wf1-bill", "bpmn", "operaton-2.2.0"));
        assertEquals("models/wf2-file.bpmn", Corpus.resolve("wf2-file", "bpmn", "flowable-8.0.0"));
        assertTrue(Corpus.hasVariant("wf3-licence", "bpmn", "flowable"));
        assertFalse(Corpus.hasVariant("wf1-delegation-of-powers", "dmn", "flowable"));
        assertArrayEquals(Corpus.dmn("wf1-delegation-of-powers", "flowable"),
                Corpus.dmn("wf1-delegation-of-powers", "operaton"));
        assertThrows(IllegalArgumentException.class, () -> Corpus.bpmn("no-such-model", "flowable"));
        assertThrows(IllegalArgumentException.class, () -> Corpus.bpmn("wf2-file", "jbpm"));
    }

    @Test
    void resourcesUseEngineNeutralDeploymentNames() {
        Map<String, byte[]> r = Corpus.resources("flowable", "wf1-bill.bpmn", "wf1-delegation-of-powers.dmn");
        assertEquals(List.of("wf1-bill.bpmn", "wf1-delegation-of-powers.dmn"), List.copyOf(r.keySet()));
        assertArrayEquals(Corpus.bpmn("wf1-bill", "flowable"), r.get("wf1-bill.bpmn"));
    }

    static boolean hasElementWithId(Document doc, String ns, String localName, String id) {
        NodeList list = doc.getElementsByTagNameNS(ns, localName);
        for (int i = 0; i < list.getLength(); i++) {
            if (id.equals(((Element) list.item(i)).getAttribute("id"))) {
                return true;
            }
        }
        return false;
    }
}
