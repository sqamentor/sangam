// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.messenger;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.integration.connector.Json;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps verified provider payloads to a neutral record. Only text content is mapped; media events carry their media reference,
 * which {@link MediaDownloads} fetches.
 */
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

    /**
     * WeChat sends XML; DOCTYPE is refused (XXE). In encrypted mode, decrypt first with {@link WeChatCrypto#decryptInbound},
     * then parse the plain XML here. An event push without {@code MsgId} (for example subscribe) uses
     * {@code FromUserName + CreateTime} as its id, the dedupe key the public document recommends.
     */
    public static Parsed weChat(byte[] xml) {
        org.w3c.dom.Document doc = secureXml(xml);
        String id = text(doc, "MsgId");
        if (id == null && text(doc, "FromUserName") != null && text(doc, "CreateTime") != null) {
            id = text(doc, "FromUserName") + ":" + text(doc, "CreateTime");
        }
        return new Parsed(id, text(doc, "FromUserName"), text(doc, "Content"), text(doc, "MediaId"));
    }

    /** The {@code Encrypt} element of an encrypted-mode body, or null when the body is in plain mode. */
    public static String weChatEncrypted(byte[] xml) {
        return text(secureXml(xml), "Encrypt");
    }

    private static org.w3c.dom.Document secureXml(byte[] xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid WeChat XML: " + e.getMessage(), e);
        }
    }

    // ---- Slack Events API ----------------------------------------------------------------------

    /** The {@code challenge} of a Slack {@code url_verification} request (after the signature check), else null. */
    public static String slackChallenge(byte[] body) {
        JsonNode root = Json.tree(body);
        return root != null && "url_verification".equals(root.path("type").asText()) ? root.path("challenge").asText(null) : null;
    }

    /**
     * A Slack {@code event_callback} with a user {@code message} event. Messages from bots (a {@code bot_id} or the
     * {@code bot_message} subtype) and edits or deletions (any other subtype) are ignored, so a bot never answers itself.
     * The id is {@code event_id}, which Slack repeats on its retries. The media reference is the first file id.
     */
    public static Parsed slack(byte[] body) {
        JsonNode root = Json.tree(body);
        if (root == null || !"event_callback".equals(root.path("type").asText())) {
            return null;
        }
        JsonNode e = root.path("event");
        if (!"message".equals(e.path("type").asText()) || e.hasNonNull("bot_id") || (e.hasNonNull("subtype") && !"file_share".equals(e.path("subtype").asText()))) {
            return null;
        }
        return new Parsed(root.path("event_id").asText(), e.path("user").asText(), e.path("text").asText(""), e.path("files").path(0).path("id").asText(null));
    }

    // ---- Matrix application service ------------------------------------------------------------

    /**
     * {@code m.room.message} events of a Matrix application-service transaction ({@code PUT /_matrix/app/v1/transactions/{txnId}}).
     * Events sent by {@code ownUserId} (the bridge's own user) are skipped. The media reference is the {@code mxc://} URL.
     */
    public static List<Parsed> matrix(byte[] body, String ownUserId) {
        List<Parsed> out = new ArrayList<>();
        JsonNode root = Json.tree(body);
        if (root == null) {
            return out;
        }
        for (JsonNode e : root.path("events")) {
            if (!"m.room.message".equals(e.path("type").asText()) || e.path("sender").asText().equals(ownUserId)) {
                continue;
            }
            JsonNode c = e.path("content");
            out.add(new Parsed(e.path("event_id").asText(), e.path("sender").asText(), c.path("body").asText(""), c.path("url").asText(null)));
        }
        return out;
    }

    private static String text(org.w3c.dom.Document d, String tag) {
        var n = d.getElementsByTagName(tag);
        return n.getLength() == 0 ? null : n.item(0).getTextContent();
    }
}
