// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.messenger;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.integration.connector.Json;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/** Maps verified provider payloads to a neutral record. Only text content is mapped; media events carry their media reference. */
public final class InboundMessageParsers {

    public record Parsed(String messageId, String externalId, String text, String mediaRef) {
    }

    private InboundMessageParsers() {
    }

    public static List<Parsed> whatsApp(byte[] body) {
        List<Parsed> out = new ArrayList<>();
        JsonNode root = Json.tree(body);
        if (root == null) {
            return out;
        }
        for (JsonNode e : root.path("entry")) {
            for (JsonNode c : e.path("changes")) {
                for (JsonNode m : c.path("value").path("messages")) {
                    String type = m.path("type").asText();
                    out.add(new Parsed(m.path("id").asText(), m.path("from").asText(), m.path("text").path("body").asText(""),
                            m.path(type).path("id").asText(null)));
                }
            }
        }
        return out;
    }

    public static Parsed teams(byte[] body) {
        JsonNode a = Json.tree(body);
        if (a == null || !"message".equals(a.path("type").asText())) {
            return null;
        }
        return new Parsed(a.path("id").asText(), a.path("from").path("aadObjectId").asText(a.path("from").path("id").asText()), a.path("text").asText(""), null);
    }

    /** What a later send to this Teams conversation needs. */
    public record TeamsReference(String serviceUrl, String conversationId, String botId, String userId) {
    }

    /** Read from an activity. Use it for a send only after {@code WebhookVerifiers.botFramework} accepted the request. */
    public static TeamsReference teamsReference(byte[] body) {
        JsonNode a = Json.tree(body);
        return a == null ? null : new TeamsReference(a.path("serviceUrl").asText(null), a.path("conversation").path("id").asText(null),
                a.path("recipient").path("id").asText(null), a.path("from").path("id").asText(null));
    }

    /** WeChat sends XML; DOCTYPE is refused (XXE). Plain text mode only: the encrypted message mode is not implemented. */
    public static Parsed weChat(byte[] xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            var doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            return new Parsed(text(doc, "MsgId"), text(doc, "FromUserName"), text(doc, "Content"), text(doc, "MediaId"));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid WeChat XML: " + e.getMessage(), e);
        }
    }

    private static String text(org.w3c.dom.Document d, String tag) {
        var n = d.getElementsByTagName(tag);
        return n.getLength() == 0 ? null : n.item(0).getTextContent();
    }
}
