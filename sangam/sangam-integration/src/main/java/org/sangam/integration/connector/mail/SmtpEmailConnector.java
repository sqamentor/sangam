// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.mail;

import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.SecretResolver;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * E-mail over SMTP (R71 "e-mail", R60 gov.in e-mail adapter, Flowable FC-07 mail task).
 *
 * <p>Input: {@code to}, {@code cc}, {@code bcc} (string with commas, or list), {@code subject}, {@code text} and/or
 * {@code html}, optional {@code attachments} (list of {@code {name, contentType, base64}}). The {@code Message-ID} is
 * {@code <operationId@messageIdDomain>}, so a duplicate send of the same operation is recognisable downstream.
 *
 * <p>Classification (A-2): a refused connection, TLS failure or 4xx reply before the message was accepted is a
 * no-effect transient failure; a 5xx refusal of a sender/recipient/message is a business failure; a failure after the
 * DATA phase started (connection reset, timeout) is an unknown outcome. SMTP has no outcome lookup, so the default
 * capability class is (c); a site that accepts duplicate mails may declare (a)... it must then accept that a retry can
 * deliver twice.
 *
 * <p>Transport security: {@code STARTTLS} required by default ({@code smtp://}); {@code smtps://} for implicit TLS.
 */
public final class SmtpEmailConnector implements SangamConnector {

    public record Config(String id, URI server, String username, String passwordRef, String from, String messageIdDomain,
                         boolean requireStartTls, Duration timeout, CapabilityClass capabilityClass, ConnectorProfile profile) {
        public Config {
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
            messageIdDomain = messageIdDomain == null ? "sangam.local" : messageIdDomain;
        }
    }

    private final Config config;
    private final EgressPolicy egress;
    private final SecretResolver secrets;
    private final ConnectorDescriptor descriptor;

    public SmtpEmailConnector(Config config, EgressPolicy egress, SecretResolver secrets) {
        this.config = config;
        this.egress = egress;
        this.secrets = secrets == null ? SecretResolver.fromEnvironment() : secrets;
        this.descriptor = new ConnectorDescriptor(config.id(), "smtp", config.capabilityClass(), List.of("send"), config.profile());
    }

    /** Simulator profile against {@code SmtpSinkSimulator} on loopback (no TLS, no auth). */
    public static SmtpEmailConnector simulator(String id, int port) {
        return new SmtpEmailConnector(new Config(id, URI.create("smtp://127.0.0.1:" + port), null, null, "noreply@sangam.local",
                "sangam.local", false, Duration.ofSeconds(10), CapabilityClass.C_NEITHER, ConnectorProfile.SIMULATOR),
                EgressPolicy.simulatorLoopback(), SecretResolver.of(Map.of()));
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest r) {
        try {
            egress.check(r.tenantId(), config.server());
        } catch (EgressPolicy.EgressDeniedException e) {
            return ConnectorResult.TransientFailure.noEffect(e.getMessage());
        }
        boolean ssl = "smtps".equalsIgnoreCase(config.server().getScheme());
        String proto = ssl ? "smtps" : "smtp";
        Properties p = new Properties();
        p.put("mail." + proto + ".host", config.server().getHost());
        p.put("mail." + proto + ".port", String.valueOf(config.server().getPort() == -1 ? (ssl ? 465 : 25) : config.server().getPort()));
        long to = Math.min(config.timeout().toMillis(), r.remainingMillis(config.timeout().toMillis()));
        p.put("mail." + proto + ".connectiontimeout", String.valueOf(Math.max(1000, to)));
        p.put("mail." + proto + ".timeout", String.valueOf(Math.max(1000, to)));
        p.put("mail." + proto + ".writetimeout", String.valueOf(Math.max(1000, to)));
        p.put("mail." + proto + ".auth", String.valueOf(config.username() != null));
        if (!ssl) {
            p.put("mail.smtp.starttls.enable", String.valueOf(config.requireStartTls()));
            p.put("mail.smtp.starttls.required", String.valueOf(config.requireStartTls()));
        }
        p.put("mail." + proto + ".ssl.checkserveridentity", "true");
        Session session = Session.getInstance(p);

        MimeMessage msg;
        Address[] recipients;
        try {
            msg = build(session, r);
            recipients = msg.getAllRecipients();
            if (recipients == null || recipients.length == 0) {
                return ConnectorResult.BusinessFailure.of("NO_RECIPIENT", "to/cc/bcc is empty");
            }
        } catch (AddressException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_ADDRESS", e.getMessage());
        } catch (MessagingException | IllegalArgumentException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_MESSAGE", e.getMessage());
        }

        Transport transport;
        try {
            transport = session.getTransport(proto);
            transport.connect(config.server().getHost(), config.username(),
                    config.passwordRef() == null ? null : secrets.require(config.passwordRef()));
        } catch (AuthenticationFailedException e) {
            return ConnectorResult.TransientFailure.noEffect("SMTP authentication failed (configuration): " + e.getMessage());
        } catch (MessagingException e) {
            return ConnectorResult.TransientFailure.noEffect("SMTP connect failed: " + e.getMessage());
        }
        try (Transport t = transport) {
            t.sendMessage(msg, recipients);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("messageId", msg.getMessageID());
            out.put("recipients", recipients.length);
            return ConnectorResult.Success.of(out, msg.getMessageID());
        } catch (SMTPSendFailedException e) {
            int code = e.getReturnCode();
            if (code >= 500) {
                return ConnectorResult.BusinessFailure.of("SMTP_" + code, e.getMessage());
            }
            if (code >= 400) {
                return ConnectorResult.TransientFailure.noEffect("SMTP " + code + ": " + e.getMessage());
            }
            return ConnectorResult.OutcomeUnknown.of("SMTP send failed: " + e.getMessage());
        } catch (SendFailedException e) {
            Address[] sent = e.getValidSentAddresses();
            if (sent == null || sent.length == 0) {
                return ConnectorResult.BusinessFailure.of("SMTP_REJECTED", e.getMessage());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("messageId", safeId(msg));
            out.put("partial", true);
            out.put("invalid", addresses(e.getInvalidAddresses()));
            return ConnectorResult.Success.of(out, safeId(msg));
        } catch (MessagingException e) {
            return causedByIo(e) ? ConnectorResult.OutcomeUnknown.of("SMTP I/O during send: " + e.getMessage())
                    : ConnectorResult.OutcomeUnknown.of("SMTP failure during send: " + e.getMessage());
        }
    }

    private MimeMessage build(Session session, ConnectorRequest r) throws MessagingException {
        String id = "<" + r.operationId() + "@" + config.messageIdDomain() + ">";
        MimeMessage msg = new MimeMessage(session) {
            @Override
            protected void updateMessageID() throws MessagingException {
                setHeader("Message-ID", id);
            }
        };
        msg.setFrom(new InternetAddress(r.str("from", config.from()), true));
        msg.setRecipients(Message.RecipientType.TO, parse(r.input().get("to")));
        msg.setRecipients(Message.RecipientType.CC, parse(r.input().get("cc")));
        msg.setRecipients(Message.RecipientType.BCC, parse(r.input().get("bcc")));
        msg.setSubject(r.str("subject", ""), "UTF-8");
        msg.setHeader("X-Sangam-Operation-Id", r.operationId().toString());
        String text = r.str("text");
        String html = r.str("html");
        Object atts = r.input().get("attachments");
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeMultipart alt = new MimeMultipart("alternative");
        if (text != null) {
            MimeBodyPart tp = new MimeBodyPart();
            tp.setText(text, "UTF-8");
            alt.addBodyPart(tp);
        }
        if (html != null) {
            MimeBodyPart hp = new MimeBodyPart();
            hp.setContent(html, "text/html; charset=UTF-8");
            alt.addBodyPart(hp);
        }
        if (alt.getCount() == 0) {
            throw new IllegalArgumentException("text or html is required");
        }
        MimeBodyPart altPart = new MimeBodyPart();
        altPart.setContent(alt);
        mixed.addBodyPart(altPart);
        if (atts instanceof Collection<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> a) {
                    MimeBodyPart ap = new MimeBodyPart();
                    byte[] data = Base64.getDecoder().decode(String.valueOf(a.get("base64")));
                    String ct = a.get("contentType") == null ? "application/octet-stream" : String.valueOf(a.get("contentType"));
                    ap.setDataHandler(new jakarta.activation.DataHandler(new jakarta.mail.util.ByteArrayDataSource(data, ct)));
                    ap.setFileName(String.valueOf(a.get("name")));
                    mixed.addBodyPart(ap);
                }
            }
        }
        msg.setContent(mixed);
        msg.saveChanges();
        return msg;
    }

    private static InternetAddress[] parse(Object v) throws AddressException {
        if (v == null) {
            return new InternetAddress[0];
        }
        if (v instanceof Collection<?> c) {
            List<InternetAddress> out = new ArrayList<>();
            for (Object o : c) {
                out.add(new InternetAddress(String.valueOf(o), true));
            }
            return out.toArray(new InternetAddress[0]);
        }
        return InternetAddress.parse(String.valueOf(v), true);
    }

    private static List<String> addresses(Address[] a) {
        List<String> out = new ArrayList<>();
        if (a != null) {
            for (Address x : a) {
                out.add(x.toString());
            }
        }
        return out;
    }

    private static String safeId(MimeMessage m) {
        try {
            return m.getMessageID();
        } catch (MessagingException e) {
            return null;
        }
    }

    private static boolean causedByIo(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof IOException) {
                return true;
            }
            if (t instanceof MessagingException me && me.getNextException() != null && me.getNextException() != t.getCause()) {
                if (me.getNextException() instanceof IOException) {
                    return true;
                }
            }
        }
        return false;
    }
}
