package org.sangam.spike.sim.dlt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sangam.spike.sim.SimHttpServer;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SMS gateway with the TRAI DLT scrubbing rules that are public (SANGAM.md IX.7e GOV-10):
 * registered template id, header bound to the template (a {@code -G} Government-message suffix
 * is accepted), message content matching the template ({@code {#var#}} = up to 30 characters),
 * and every URL in the message starting with a whitelisted fixed part.
 *
 * <p>{@code POST /dlt/v1/sms} with JSON {@code {header, templateId, mobile, message}} →
 * 200 {@code {"status":"ACCEPTED","messageId":...}} or 400 {@code {"status":"REJECTED","reason":...}}
 * where reason is one of {@code TEMPLATE_NOT_REGISTERED, HEADER_MISMATCH, CONTENT_MISMATCH,
 * URL_NOT_WHITELISTED, INVALID_MOBILE, BAD_REQUEST}. Ledger type {@code SMS_SENT}, key = messageId.
 */
public final class DltSmsSimulator extends SimHttpServer {

    public static final String SMS_PATH = "/dlt/v1/sms";
    public static final String EFFECT_SMS = "SMS_SENT";
    public static final String GOV_SUFFIX = "-G";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern URL = Pattern.compile("(?i)\\b((?:https?://|www\\.)\\S+)");
    private static final Pattern MOBILE = Pattern.compile("(?:91)?[6-9]\\d{9}");
    private static final String VAR = "{#var#}";

    private record Template(String id, String header, Pattern content) { }

    private final Map<String, Template> templates = new ConcurrentHashMap<>();
    private final List<String> urlWhitelist = new CopyOnWriteArrayList<>();
    private final AtomicLong msgSeq = new AtomicLong();

    public DltSmsSimulator() {
        super("dlt-sms");
        route("POST", SMS_PATH, this::send);
    }

    /** Register a content template bound to one header. {@code body} uses {@code {#var#}} placeholders. */
    public DltSmsSimulator registerTemplate(String templateId, String header, String body) {
        StringBuilder re = new StringBuilder();
        int from = 0;
        int at;
        while ((at = body.indexOf(VAR, from)) >= 0) {
            re.append(Pattern.quote(body.substring(from, at))).append("(.{1,30})");
            from = at + VAR.length();
        }
        re.append(Pattern.quote(body.substring(from)));
        templates.put(templateId, new Template(templateId, header, Pattern.compile(re.toString(), Pattern.DOTALL)));
        return this;
    }

    /** Whitelist the fixed part of a URL (e.g. {@code https://dept.gov.in/track/}). */
    public DltSmsSimulator whitelistUrl(String fixedPrefix) {
        urlWhitelist.add(fixedPrefix.toLowerCase(Locale.ROOT));
        return this;
    }

    /** Validation result; {@code reason} is null when the message is acceptable. */
    public String validate(String header, String templateId, String mobile, String message) {
        if (header == null || templateId == null || mobile == null || message == null) {
            return "BAD_REQUEST";
        }
        Template t = templates.get(templateId);
        if (t == null) {
            return "TEMPLATE_NOT_REGISTERED";
        }
        String base = header.endsWith(GOV_SUFFIX) ? header.substring(0, header.length() - GOV_SUFFIX.length()) : header;
        if (!base.equals(t.header()) && !header.equals(t.header())) {
            return "HEADER_MISMATCH";
        }
        if (!MOBILE.matcher(mobile).matches()) {
            return "INVALID_MOBILE";
        }
        if (!t.content().matcher(message).matches()) {
            return "CONTENT_MISMATCH";
        }
        Matcher m = URL.matcher(message);
        while (m.find()) {
            String url = m.group(1).replaceAll("[.,;:!?)\\]]+$", "").toLowerCase(Locale.ROOT);
            String normalised = url.startsWith("www.") ? "https://" + url : url;
            boolean ok = urlWhitelist.stream().anyMatch(p -> url.startsWith(p) || normalised.startsWith(p));
            if (!ok) {
                return "URL_NOT_WHITELISTED";
            }
        }
        return null;
    }

    private Response send(Request req) {
        JsonNode b;
        try {
            b = JSON.readTree(req.body());
        } catch (Exception e) {
            b = null;
        }
        if (b == null || !b.isObject()) {
            return reject("BAD_REQUEST");
        }
        String header = text(b, "header");
        String templateId = text(b, "templateId");
        String mobile = text(b, "mobile");
        String message = text(b, "message");
        String reason = validate(header, templateId, mobile, message);
        if (reason != null) {
            return reject(reason);
        }
        String id = "DLT" + String.format("%08d", msgSeq.incrementAndGet());
        ledger().record(EFFECT_SMS, id, Map.of("header", header, "templateId", templateId, "mobile", mobile,
                "message", message));
        ObjectNode out = JSON.createObjectNode();
        out.put("status", "ACCEPTED");
        out.put("messageId", id);
        return Response.json(200, out.toString());
    }

    private static String text(JsonNode b, String field) {
        JsonNode n = b.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static Response reject(String reason) {
        ObjectNode out = JSON.createObjectNode();
        out.put("status", "REJECTED");
        out.put("reason", reason);
        return Response.json(400, out.toString());
    }
}
