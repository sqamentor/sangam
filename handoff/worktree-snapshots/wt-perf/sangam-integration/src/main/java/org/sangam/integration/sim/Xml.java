// SPDX-License-Identifier: Apache-2.0
// Ported from spike/simulators org.sangam.spike.sim.Xml. Modified by Sangam.
package org.sangam.integration.sim;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Hardened DOM parsing (no DOCTYPE, no external entities: XXE / billion-laughs safe, R27) and small
 * helpers for the XML simulators. Parsing is not namespace aware, so tag names keep their prefix;
 * {@link #elementsByLocalName(Element, String)} matches {@code soap:Body} and {@code Body} alike.
 */
public final class Xml {

    private Xml() { }

    public static Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder b = f.newDocumentBuilder();
        b.setErrorHandler( // report by exception only, not on stderr
                new org.xml.sax.helpers.DefaultHandler() {
                    @Override
                    public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                        throw e;
                    }
                });
        return b.parse(new ByteArrayInputStream(bytes));
    }

    /** Direct or nested child elements named {@code name}, in document order. */
    public static List<Element> elements(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getElementsByTagName(name);
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i).getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) nl.item(i));
            }
        }
        return out;
    }

    /** Trimmed text of the first descendant element named {@code name}, or "" if absent. */
    public static String text(Element parent, String name) {
        List<Element> l = elements(parent, name);
        return l.isEmpty() ? "" : l.get(0).getTextContent().trim();
    }

    /** Local part of a (possibly prefixed) tag name. */
    public static String localName(Element e) {
        String t = e.getTagName();
        int colon = t.indexOf(':');
        return colon < 0 ? t : t.substring(colon + 1);
    }

    /** Descendant elements whose local name is {@code localName} (any prefix), in document order. */
    public static List<Element> elementsByLocalName(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getElementsByTagName("*");
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element e && localName(e).equals(localName)) {
                out.add(e);
            }
        }
        return out;
    }

    /** Trimmed text of the first descendant with that local name, or "" if absent. */
    public static String textByLocalName(Element parent, String localName) {
        List<Element> l = elementsByLocalName(parent, localName);
        return l.isEmpty() ? "" : l.get(0).getTextContent().trim();
    }

    public static String esc(String s) {
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
