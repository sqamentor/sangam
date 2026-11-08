// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.connector;

import org.sangam.core.integration.ConnectorResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared HTTP plumbing for the OGC connectors: query building, egress allow-list (air-gapped by default, ARCHITECTURE
 * §1.9), outcome mapping and XXE-safe XML parsing. OGC GetMap/GetFeature/GetFeatureInfo/GetCapabilities are reads, so
 * every failure is reported as {@code noEffect}: retrying is always safe.
 */
public final class GisHttp {

    /**
     * Connection settings of one external GIS service.
     *
     * @param allowedHosts egress allow-list; empty means no outbound call is permitted
     * @param headers      static headers (for example an API key header); never logged
     */
    public record Config(String baseUrl, Set<String> allowedHosts, Map<String, String> headers, Duration timeout,
                         long maxResponseBytes) {
        public Config {
            allowedHosts = allowedHosts == null ? Set.of() : Set.copyOf(allowedHosts);
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
            maxResponseBytes = maxResponseBytes <= 0 ? 32L * 1024 * 1024 : maxResponseBytes;
        }
    }

    public record Response(int status, String contentType, byte[] body) {
        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private final Config config;
    private final HttpClient client;

    public GisHttp(Config config) {
        this.config = config;
        this.client = HttpClient.newBuilder().connectTimeout(config.timeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Config config() {
        return config;
    }

    public static String query(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (v != null) {
                sb.append(sb.isEmpty() ? "" : "&").append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                        .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
            }
        });
        return sb.toString();
    }

    public URI uri(String baseUrl, Map<String, String> params) {
        String base = baseUrl == null || baseUrl.isBlank() ? config.baseUrl() : baseUrl;
        return URI.create(base + (base.contains("?") ? "&" : "?") + query(params));
    }

    /** Checks the egress allow-list; returns null when allowed, otherwise a failure. */
    public ConnectorResult egressCheck(URI uri) {
        String host = uri.getHost();
        if (host == null || !config.allowedHosts().contains(host.toLowerCase())) {
            return ConnectorResult.BusinessFailure.of("EGRESS_DENIED", "host not in the GIS egress allow-list: " + host);
        }
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
            return ConnectorResult.BusinessFailure.of("EGRESS_DENIED", "unsupported scheme");
        }
        return null;
    }

    /** GET with the connector's headers; {@code null} result body on transport failure is signalled by exception. */
    public Response get(URI uri, String accept, long remainingMillis) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).GET()
                .timeout(Duration.ofMillis(Math.max(1_000, Math.min(remainingMillis, config.timeout().toMillis()))));
        if (accept != null) {
            b.header("Accept", accept);
        }
        config.headers().forEach(b::header);
        HttpResponse<byte[]> r = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        byte[] body = r.body();
        if (body != null && body.length > config.maxResponseBytes()) {
            throw new IOException("response exceeds " + config.maxResponseBytes() + " bytes");
        }
        return new Response(r.statusCode(), r.headers().firstValue("Content-Type").orElse(""), body == null ? new byte[0] : body);
    }

    /** Maps a non-2xx status: 4xx is a business failure (bad request/layer), 5xx/429 transient (no effect). */
    public static ConnectorResult statusFailure(Response r) {
        if (r.status() == 429 || r.status() >= 500) {
            return new ConnectorResult.TransientFailure("GIS service HTTP " + r.status(), true, Duration.ofSeconds(5));
        }
        return ConnectorResult.BusinessFailure.of("GIS_HTTP_" + r.status(), truncate(r.text(), 500));
    }

    /** OGC ServiceExceptionReport / ows:ExceptionReport in a 200 response. */
    public static String ogcException(Response r) {
        String t = r.contentType().toLowerCase();
        if (!(t.contains("xml") || t.isEmpty())) {
            return null;
        }
        String s = r.text();
        if (s.contains("ServiceException") || s.contains("ExceptionReport")) {
            return truncate(s, 500);
        }
        return null;
    }

    public static ConnectorResult transportFailure(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return new ConnectorResult.TransientFailure("GIS service unreachable: " + e.getClass().getSimpleName(), true,
                Duration.ofSeconds(10));
    }

    public static String truncate(String s, int n) {
        return s == null ? null : s.length() <= n ? s : s.substring(0, n);
    }

    // ------------------------------------------------------------------ hardened XML

    public static Document parseXml(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(xml));
    }

    /** Names and titles of the {@code Layer} (WMS) or {@code FeatureType} (WFS) elements of a capabilities document. */
    public static List<Map<String, String>> capabilityEntries(Document doc, String element) {
        List<Map<String, String>> out = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagNameNS("*", element);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            String name = childText(e, "Name");
            if (name == null) {
                continue;
            }
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", name);
            String title = childText(e, "Title");
            if (title != null) {
                m.put("title", title);
            }
            out.add(m);
        }
        return out;
    }

    private static String childText(Element e, String local) {
        for (org.w3c.dom.Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && local.equals(c.getLocalName())) {
                return c.getTextContent().strip();
            }
        }
        return null;
    }
}
