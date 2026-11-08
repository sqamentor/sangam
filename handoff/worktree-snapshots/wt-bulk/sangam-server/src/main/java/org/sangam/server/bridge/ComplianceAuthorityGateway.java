// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.ports.CompliancePorts;

/**
 * {@link CompliancePorts.SupervisoryAuthorityGateway} (IN: Data Protection Board) and
 * {@link CompliancePorts.IncidentAuthorityGateway} (IN: CERT-In, state CERTs) over the integration connectors: a
 * recipient with {@code @} is sent by the e-mail connector, an {@code http(s)} recipient by the HTTP connector (POST of
 * the JSON content). Each (case, kind, recipient) is one operation identity, so a re-submission returns the first
 * result instead of reporting twice. Recipients for the supervisory authority come from the tenant configuration
 * ({@code authority.<code>.recipients}, defaulted by the jurisdiction pack).
 */
public final class ComplianceAuthorityGateway implements CompliancePorts.SupervisoryAuthorityGateway,
        CompliancePorts.IncidentAuthorityGateway {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
            .findAndRegisterModules();

    private final ComplianceConnectors connectors;
    private final ComplianceConfig.Provider config;
    private final String emailConnector;
    private final String httpConnector;

    public ComplianceAuthorityGateway(ComplianceConnectors connectors, ComplianceConfig.Provider config,
                                      String emailConnector, String httpConnector) {
        this.connectors = connectors;
        this.config = config;
        this.emailConnector = emailConnector;
        this.httpConnector = httpConnector;
    }

    @Override
    public String submit(CompliancePorts.AuthoritySubmission s) {
        List<String> recipients = config.forTenant(s.tenantId()).recipients(s.authorityCode());
        if (recipients == null || recipients.isEmpty()) {
            throw new ComplianceException(ComplianceException.Code.REPORT_INCOMPLETE,
                    "no recipient configured for authority " + s.authorityCode());
        }
        List<String> refs = new ArrayList<>();
        for (String r : recipients) {
            refs.add(send(s.tenantId(), s.caseId(), s.kind() + ":" + s.authorityCode(), r,
                    s.kind() + " — " + s.authorityCode() + " — " + s.caseId(), s.content()));
        }
        return String.join(",", refs);
    }

    @Override
    public String submit(CompliancePorts.IncidentSubmission s) {
        String kind = (s.followUp() ? "incident-follow-up:" : "incident:") + s.authorityCode();
        return send(s.tenantId(), s.incidentId(), kind, s.recipient(),
                (s.followUp() ? "Follow-up: " : "") + "Cyber security incident report " + s.incidentId(), s.form());
    }

    private String send(String tenant, String caseId, String kind, String recipient, String subject,
                        Map<String, Object> content) {
        String text;
        try {
            text = JSON.writeValueAsString(content == null ? Map.of() : content);
        } catch (Exception e) {
            throw new IllegalArgumentException("authority report is not serialisable", e);
        }
        Map<String, Object> in = new LinkedHashMap<>();
        String connector;
        String operation;
        if (recipient.startsWith("http://") || recipient.startsWith("https://")) {
            connector = httpConnector;
            operation = "post";
            in.put("method", "POST");
            in.put("path", recipient);
            in.put("body", content == null ? Map.of() : content);
        } else if (recipient.contains("@")) {
            connector = emailConnector;
            operation = "send";
            in.put("to", recipient);
            in.put("subject", subject);
            in.put("text", text);
        } else {
            throw new ComplianceException(ComplianceException.Code.INVALID_INPUT,
                    "authority recipient must be an e-mail address or an http(s) URL: " + recipient);
        }
        ComplianceConnectors.Result r = connectors.call(tenant, caseId, kind + ":" + recipient, connector, operation, in);
        if (!r.ok()) {
            throw new ComplianceException(ComplianceException.Code.INVALID_STATE,
                    "submission to " + recipient + " not confirmed: " + r.code() + " " + r.message()
                            + (r.reference() != null ? " (operation " + r.reference() + ")" : ""));
        }
        return r.reference();
    }
}
