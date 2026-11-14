// SPDX-License-Identifier: Apache-2.0
package org.sangam.feel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.operaton.bpm.dmn.engine.DmnEngineException;
import org.operaton.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration;
import org.operaton.bpm.dmn.engine.impl.spi.transform.DmnTransform;
import org.operaton.bpm.dmn.engine.impl.spi.transform.DmnTransformFactory;
import org.operaton.bpm.dmn.engine.impl.spi.transform.DmnTransformer;
import org.operaton.bpm.dmn.engine.impl.transform.DefaultDmnTransform;
import org.operaton.bpm.dmn.engine.impl.transform.DefaultDmnTransformer;
import org.operaton.bpm.model.dmn.Dmn;
import org.operaton.bpm.model.dmn.DmnModelInstance;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Decision 158 (M-126): engine-side acceptance of DMN 1.6 documents on Operaton 2.2.0, whose model API knows DMN 1.1
 * to 1.5 only.
 *
 * <p>Evidence (decision 206 downloads, DOWNLOADS-WAVE4.md 2026-11-13): the normative DMN 1.6 XSD (OMG dtc/24-05-20,
 * {@code DMN16.xsd}) differs from the DMN 1.5 XSD that Operaton bundles ({@code DMN15.xsd}) in exactly three places:
 * the target namespace ({@value #DMN16_NS} instead of {@value #DMN15_NS}), the default of {@code expressionLanguage} and
 * {@code typeLanguage} ({@value #FEEL16_NS} instead of {@value #FEEL15_NS}) and the extra {@code tFunctionKind} value
 * {@code ONNX}. The diagram interchange schema (DMNDI 1.5) is the same file. So a DMN 1.6 document is valid exactly when
 * the same document, with the 1.6 model namespace replaced by the 1.5 one, is valid against DMN15.xsd and uses no
 * {@code kind="ONNX"} function.
 *
 * <p>What this class does, for a document whose root element is in the DMN 1.6 model namespace only (every other
 * document is passed through byte for byte):
 * <ol>
 *   <li>refuses {@code functionDefinition kind="ONNX"} ({@value #CODE_ONNX}; Operaton has no ONNX runtime) and the
 *       B-FEEL dialect ({@value #CODE_BFEEL}; Operaton evaluates FEEL only), each with a clear code instead of a late
 *       evaluation error;</li>
 *   <li>renames, in an in-memory copy, every element and attribute of the 1.6 model namespace to the 1.5 one and maps
 *       the FEEL 1.6 {@code expressionLanguage} and {@code typeLanguage} value to the FEEL 1.5 value, so Operaton reads
 *       it as FEEL;</li>
 *   <li>hands the copy to Operaton's own parser, which validates it against the bundled DMN15.xsd (schema validation
 *       is never skipped).</li>
 * </ol>
 * The deployed resource bytes are never changed: the repository keeps the author's DMN 1.6 document, and every
 * parse (deployment, cache rebuild after a restart, standalone DMN engines) goes through the same conversion because
 * {@link SangamFeel#install} puts {@link #install} on every Sangam DMN engine configuration.
 *
 * <p>FEEL 1.6 changes (specification formal/25-12-02, clause 10.3.4): {@code date and time(date, time, timezone)} is
 * evaluated by the pinned FEEL engine; IANA zone ids in time literals are deprecated (warning
 * {@code SANGAM-DMN-TIME-IANA} in {@code org.sangam.engine.feel.DmnModelLint}). No conformance claim is made until the tests run.
 *
 * <p>Limits: {@code RepositoryService#getDmnModelInstance} reads stored bytes with Operaton's model API directly and
 * does not know DMN 1.6; callers that need a model instance of a stored resource use {@link #readModel(byte[])}.
 */
public final class Dmn16Support {

    public static final String DMN15_NS = "https://www.omg.org/spec/DMN/20230324/MODEL/";
    public static final String DMN16_NS = "https://www.omg.org/spec/DMN/20240513/MODEL/";
    public static final String FEEL15_NS = "https://www.omg.org/spec/DMN/20230324/FEEL/";
    public static final String FEEL16_NS = "https://www.omg.org/spec/DMN/20240513/FEEL/";
    public static final String BFEEL15_NS = "https://www.omg.org/spec/DMN/20230324/B-FEEL/";
    public static final String BFEEL16_NS = "https://www.omg.org/spec/DMN/20240513/B-FEEL/";

    public static final String CODE_ONNX = "SANGAM-DMN16-ONNX-UNSUPPORTED";
    public static final String CODE_BFEEL = "SANGAM-DMN16-BFEEL-UNSUPPORTED";
    public static final String CODE_UNREADABLE = "SANGAM-DMN16-UNREADABLE";

    private static final String XMLNS_NS = XMLConstants.XMLNS_ATTRIBUTE_NS_URI;

    private Dmn16Support() {
    }

    /** Refused DMN 1.6 content, with a stable code. */
    public static final class Dmn16Exception extends DmnEngineException {
        private final String code;

        Dmn16Exception(String code, String message, Throwable cause) {
            super(code + ": " + message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * Puts the DMN 1.6 conversion in front of the parser of this configuration. Idempotent. The transformer's listeners
     * and registries are kept (same objects), so handlers added before or after this call stay in effect.
     */
    public static DefaultDmnEngineConfiguration install(DefaultDmnEngineConfiguration dmn) {
        DmnTransformer current = dmn.getTransformer();
        if (current instanceof Transformer16) {
            return dmn;
        }
        if (!(current instanceof DefaultDmnTransformer base)) {
            throw new IllegalStateException("decision 158: DMN 1.6 support needs Operaton's DefaultDmnTransformer, found "
                    + (current == null ? "null" : current.getClass().getName()));
        }
        dmn.setTransformer(new Transformer16(base));
        return dmn;
    }

    /** True when the document's root element is in the DMN 1.6 model namespace. */
    public static boolean isDmn16(byte[] xml) {
        return DMN16_NS.equals(parse(xml).getDocumentElement().getNamespaceURI());
    }

    /**
     * The bytes Operaton's parser gets: the input unchanged unless the root element is DMN 1.6, else the converted
     * DMN 1.5 form (refusing ONNX functions and B-FEEL first).
     */
    public static byte[] toParserInput(byte[] xml) {
        Document doc = parse(xml);
        Element root = doc.getDocumentElement();
        if (!DMN16_NS.equals(root.getNamespaceURI())) {
            return xml;
        }
        refuseUnsupported(root);
        convert(doc, root);
        return serialise(doc);
    }

    /** A validated Operaton model instance of a DMN 1.1 to 1.6 document. */
    public static DmnModelInstance readModel(byte[] xml) {
        return Dmn.readModelFromStream(new ByteArrayInputStream(toParserInput(xml)));
    }

    private static void refuseUnsupported(Element root) {
        NodeList all = root.getOwnerDocument().getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (DMN16_NS.equals(e.getNamespaceURI()) && "functionDefinition".equals(e.getLocalName())
                    && "ONNX".equals(e.getAttribute("kind").trim())) {
                throw new Dmn16Exception(CODE_ONNX, "functionDefinition '" + e.getAttribute("id")
                        + "' has kind=\"ONNX\"; this engine evaluates FEEL only", null);
            }
            for (String a : new String[] {"expressionLanguage", "typeLanguage"}) {
                String v = e.getAttribute(a).trim();
                if (BFEEL16_NS.equals(v) || BFEEL15_NS.equals(v)) {
                    throw new Dmn16Exception(CODE_BFEEL, e.getLocalName() + " '" + e.getAttribute("id") + "' sets " + a
                            + "=\"" + v + "\"; the B-FEEL dialect is not supported, use FEEL (" + FEEL16_NS + ")", null);
                }
            }
        }
    }

    private static void convert(Document doc, Element root) {
        NodeList all = doc.getElementsByTagNameNS("*", "*");
        // collect first: renameNode may replace nodes while the live list is walked
        Element[] elements = new Element[all.getLength()];
        for (int i = 0; i < elements.length; i++) {
            elements[i] = (Element) all.item(i);
        }
        for (Element e : elements) {
            NamedNodeMap attrs = e.getAttributes();
            Attr[] copy = new Attr[attrs.getLength()];
            for (int i = 0; i < copy.length; i++) {
                copy[i] = (Attr) attrs.item(i);
            }
            for (Attr a : copy) {
                if (XMLNS_NS.equals(a.getNamespaceURI())) {
                    if (DMN16_NS.equals(a.getValue())) {
                        a.setValue(DMN15_NS);
                    }
                } else if (DMN16_NS.equals(a.getNamespaceURI())) {
                    doc.renameNode(a, DMN15_NS, a.getName());
                } else if (a.getNamespaceURI() == null
                        && ("expressionLanguage".equals(a.getLocalName()) || "typeLanguage".equals(a.getLocalName()))
                        && FEEL16_NS.equals(a.getValue().trim())) {
                    a.setValue(FEEL15_NS);
                } else if (a.getNamespaceURI() == null && "importType".equals(a.getLocalName())
                        && DMN16_NS.equals(a.getValue().trim())) {
                    a.setValue(DMN15_NS); // an imported DMN 1.6 model is read the same way
                }
            }
            if (DMN16_NS.equals(e.getNamespaceURI())) {
                doc.renameNode(e, DMN15_NS, e.getTagName());
            }
        }
    }

    private static Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new Dmn16Exception(CODE_UNREADABLE, "cannot read DMN document: " + e.getMessage(), e);
        }
    }

    private static byte[] serialise(Document doc) {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();
        } catch (Exception e) {
            throw new Dmn16Exception(CODE_UNREADABLE, "cannot write the converted DMN document: " + e.getMessage(), e);
        }
    }

    private static byte[] readAll(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Operaton's transformer with the DMN 1.6 conversion in front of every stream parse. */
    static final class Transformer16 extends DefaultDmnTransformer {
        Transformer16(DefaultDmnTransformer base) {
            this.transformFactory = new Factory16();
            this.transformListeners = base.getTransformListeners();
            this.elementTransformHandlerRegistry = base.getElementTransformHandlerRegistry();
            this.dataTypeTransformerRegistry = base.getDataTypeTransformerRegistry();
            this.hitPolicyHandlerRegistry = base.getHitPolicyHandlerRegistry();
        }
    }

    static final class Factory16 implements DmnTransformFactory {
        @Override
        public DmnTransform createTransform(DmnTransformer transformer) {
            return new Transform16(transformer);
        }
    }

    static final class Transform16 extends DefaultDmnTransform {
        Transform16(DmnTransformer transformer) {
            super(transformer);
        }

        @Override
        public void setModelInstance(InputStream inputStream) {
            if (inputStream == null) {
                super.setModelInstance(inputStream); // Operaton's own null check and message
                return;
            }
            super.setModelInstance(new ByteArrayInputStream(toParserInput(readAll(inputStream))));
        }

        @Override
        public void setModelInstance(java.io.File file) {
            if (file == null) {
                super.setModelInstance(file);
                return;
            }
            try {
                setModelInstance(java.nio.file.Files.newInputStream(file.toPath()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
