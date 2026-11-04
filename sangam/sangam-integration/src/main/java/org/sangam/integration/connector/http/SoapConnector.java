// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.http;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.HttpTransport;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SOAP 1.1 / 1.2 connector (R71; Flowable FC-50 web-service task; Operaton connect-plugin SOAP parity B-16).
 *
 * <p>Input: {@code soapAction}, {@code bodyXml} (the payload element(s) to place in {@code soap:Body}; well-formedness
 * is checked with a hardened parser), optional {@code headerXml}. A WS-Addressing {@code MessageID} header
 * {@code urn:uuid:<operationId>} is always added, so idempotent services can dedup (A-2). The response body content is
 * returned as {@code responseXml}; a SOAP Fault becomes a business failure with the fault code, except
 * {@code Server}/{@code Receiver} faults which leave the outcome unknown (the service may have acted).
 *
 * <p>XML parsing disallows DOCTYPE and external entities (XXE).
 */
public final class SoapConnector implements SangamConnector {

    public enum Version { SOAP_11, SOAP_12 }

    public record Config(String id, URI endpoint, Version version, CapabilityClass capabilityClass, Duration timeout,
                         Map<String, String> headers, ConnectorProfile profile) {
        public Config {
            version = version == null ? Version.SOAP_11 : version;
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    private static final String NS11 = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String NS12 = "http://www.w3.org/2003/05/soap-envelope";
    private static final String WSA = "http://www.w3.org/2005/08/addressing";

    private final Config config;
    private final HttpTransport transport;
    private final ConnectorDescriptor descriptor;

    public SoapConnector(Config config, HttpTransport transport) {
        this.config = config;
        this.transport = transport;
        this.descriptor = new ConnectorDescriptor(config.id(), "soap", config.capabilityClass(), List.of(), config.profile());
    }

    /** Simulator profile against {@code SoapEchoSimulator} ({@code POST /soap/echo}). */
    public static SoapConnector simulator(String id, URI simulatorBase) {
        return new SoapConnector(new Config(id, simulatorBase.resolve("/soap/echo"), Version.SOAP_11,
                CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, Duration.ofSeconds(10), Map.of(), ConnectorProfile.SIMULATOR),
                HttpTransport.forSimulator());
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest r) {
        String bodyXml = r.str("bodyXml");
        if (bodyXml == null || bodyXml.isBlank()) {
            return ConnectorResult.BusinessFailure.of("INVALID_REQUEST", "bodyXml is required");
        }
        try {
            parse(("<w>" + bodyXml + "</w>").getBytes(StandardCharsets.UTF_8));
            if (r.str("headerXml") != null) {
                parse(("<w>" + r.str("headerXml") + "</w>").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            return ConnectorResult.BusinessFailure.of("INVALID_XML", e.getMessage());
        }
        boolean v12 = config.version() == Version.SOAP_12;
        String ns = v12 ? NS12 : NS11;
        String envelope = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soap:Envelope xmlns:soap=\"" + ns + "\" xmlns:wsa=\"" + WSA + "\"><soap:Header>"
                + "<wsa:MessageID>urn:uuid:" + r.operationId() + "</wsa:MessageID>"
                + (r.str("soapAction") == null ? "" : "<wsa:Action>" + escape(r.str("soapAction")) + "</wsa:Action>")
                + r.str("headerXml", "")
                + "</soap:Header><soap:Body>" + bodyXml + "</soap:Body></soap:Envelope>";
        Map<String, String> headers = new LinkedHashMap<>(config.headers());
        String action = r.str("soapAction", "");
        if (v12) {
            headers.put("Content-Type", "application/soap+xml; charset=utf-8" + (action.isEmpty() ? "" : "; action=\"" + action + "\""));
        } else {
            headers.put("Content-Type", "text/xml; charset=utf-8");
            headers.put("SOAPAction", "\"" + action + "\"");
        }
        headers.put("X-Sangam-Operation-Id", r.operationId().toString());
        boolean idempotent = config.capabilityClass() == CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP;
        HttpTransport.Outcome o = transport.execute(r.tenantId(), new HttpTransport.Call("POST", config.endpoint(), headers,
                envelope.getBytes(StandardCharsets.UTF_8), config.timeout(), idempotent),
                r.deadline() == null ? 0 : r.deadline().toEpochMilli());
        if (!(o instanceof HttpTransport.Outcome.Response resp) || resp.body().length == 0) {
            return HttpRestConnector.map(o, idempotent);
        }
        Document doc;
        try {
            doc = parse(resp.body());
        } catch (Exception e) {
            return resp.is2xx() ? ConnectorResult.OutcomeUnknown.of("2xx with an unparseable SOAP body")
                    : HttpRestConnector.map(o, idempotent);
        }
        Element body = firstChild(doc.getDocumentElement(), "Body");
        if (body == null) {
            return ConnectorResult.OutcomeUnknown.of("SOAP response without Body (HTTP " + resp.status() + ")");
        }
        Element fault = firstChild(body, "Fault");
        if (fault != null) {
            String code = v12 ? text(firstChild(firstChild(fault, "Code"), "Value")) : text(firstChild(fault, "faultcode"));
            String reason = v12 ? text(firstChild(firstChild(fault, "Reason"), "Text")) : text(firstChild(fault, "faultstring"));
            String local = code == null ? "" : code.substring(code.indexOf(':') + 1);
            if (local.equals("Server") || local.equals("Receiver")) {
                return idempotent ? new ConnectorResult.TransientFailure("SOAP fault " + code + ": " + reason, false, null)
                        : ConnectorResult.OutcomeUnknown.of("SOAP server fault " + code + ": " + reason);
            }
            return new ConnectorResult.BusinessFailure("SOAP_FAULT_" + local, reason, Map.of("faultCode", String.valueOf(code)));
        }
        if (!resp.is2xx()) {
            return HttpRestConnector.map(o, idempotent);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", resp.status());
        out.put("responseXml", innerXml(body));
        return ConnectorResult.Success.of(out, null);
    }

    private static Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
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

    private static Element firstChild(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                String ln = n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
                if (ln.equals(localName) || ln.endsWith(":" + localName)) {
                    return (Element) n;
                }
            }
        }
        return null;
    }

    private static String text(Element e) {
        return e == null ? null : e.getTextContent().trim();
    }

    private static String innerXml(Element e) {
        try {
            var tf = javax.xml.transform.TransformerFactory.newInstance();
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var t = tf.newTransformer();
            t.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter w = new StringWriter();
            NodeList nl = e.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                t.transform(new javax.xml.transform.dom.DOMSource(nl.item(i)), new javax.xml.transform.stream.StreamResult(w));
            }
            return w.toString();
        } catch (Exception ex) {
            return e.getTextContent();
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
