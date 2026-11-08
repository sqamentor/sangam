// SPDX-License-Identifier: Apache-2.0
// Ported from spike/simulators org.sangam.spike.sim.Xml. Modified by Sangam.
package org.sangam.integration.connector.gov;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Hardened XML parsing for the XML-speaking government connectors (eSign, DigiLocker; R27, T23): DOCTYPE
 * declarations are refused (no XXE, no entity expansion), external DTD/schema access is off, secure processing is on,
 * and XInclude is off. Kept separate from the simulator helper so production connectors do not depend on simulator
 * code.
 */
final class GovXml {

    private GovXml() {
    }

    static Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override
            public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                throw e;
            }
        });
        return b.parse(new ByteArrayInputStream(bytes));
    }

    /** Descendant elements named {@code name}, in document order. */
    static List<Element> elements(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getElementsByTagName(name);
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i).getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) nl.item(i));
            }
        }
        return out;
    }

    /** Trimmed text of the first descendant named {@code name}, or "" if absent. */
    static String text(Element parent, String name) {
        List<Element> l = elements(parent, name);
        return l.isEmpty() ? "" : l.get(0).getTextContent().trim();
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
