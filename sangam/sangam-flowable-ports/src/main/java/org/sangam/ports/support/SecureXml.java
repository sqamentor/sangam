// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.support;

import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Hardened XML parsing for every model the ports module reads or rewrites (D-51, R24): DOCTYPE declarations are
 * rejected outright, external entities and XInclude are off, and secure processing is on. Nothing here uses
 * Flowable's {@code ProcessDiagramLayoutFactory} (XXE CVE-2026-91197; not ported by decision).
 */
public final class SecureXml {

    /** FW-01/FW-02 size guard: no model the ports read may exceed this (a BPMN of 10 MiB is far beyond any real model). */
    public static final int MAX_MODEL_BYTES = 10 * 1024 * 1024;
    /** Maximum element nesting depth (JDK parser property; ignored by parsers that do not know it). */
    public static final int MAX_ELEMENT_DEPTH = 256;

    private SecureXml() {
    }

    public static DocumentBuilder builder() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            try {
                f.setAttribute("jdk.xml.maxElementDepth", MAX_ELEMENT_DEPTH);
            } catch (IllegalArgumentException notJdkParser) {
                // a non-JDK parser: secure processing and disallow-doctype still apply
            }
            return f.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("cannot create a hardened XML parser", e);
        }
    }

    /** True when the document declares a DOCTYPE (rejected at deploy, D-51). */
    public static boolean hasDoctype(byte[] xml) {
        if (xml == null) {
            return false;
        }
        String head = new String(xml, 0, Math.min(xml.length, 4096), StandardCharsets.UTF_8);
        return head.contains("<!DOCTYPE") || head.contains("<!ENTITY");
    }

    /**
     * Parses a model. Rejects null/empty input, more than {@link #MAX_MODEL_BYTES} bytes ({@code MODEL_TOO_LARGE}) and
     * any DOCTYPE/ENTITY (the pre-check reads the first 4 KiB; the parser feature {@code disallow-doctype-decl} rejects a
     * DOCTYPE anywhere, in any encoding).
     */
    public static Document parse(byte[] xml) {
        if (xml == null || xml.length == 0) {
            throw PortsProblem.invalid("empty XML model");
        }
        if (xml.length > MAX_MODEL_BYTES) {
            throw PortsProblem.tooLarge("MODEL_TOO_LARGE", "XML model", xml.length, MAX_MODEL_BYTES);
        }
        if (hasDoctype(xml)) {
            throw new IllegalArgumentException("DOCTYPE/ENTITY declarations are not allowed in models (D-51)");
        }
        try {
            return builder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid XML: " + e.getMessage(), e);
        }
    }

    public static byte[] write(Document doc) {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialise XML", e);
        }
    }
}
