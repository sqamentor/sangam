// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.pas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sangam.connectors.health.HealthHttp;
import org.sangam.connectors.health.fhir.FhirClient;
import org.sangam.connectors.health.fhir.FhirConnector;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.Json;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Da Vinci flow for prior authorisation (US-HLT-060, 070–073): DTR questionnaire package, PAS submit / inquire /
 * cancel, on the generic {@link FhirClient}.
 *
 * <p>Operations:
 * <ul>
 *   <li>{@code questionnairePackage} (DTR): {@code Questionnaire/$questionnaire-package} with the coverage and the
 *       order; output {@code questionnaires} (the Questionnaire resources) and {@code bundle}. No effect.</li>
 *   <li>{@code submit} (PAS): {@code Claim/$submit} with a PAS request Bundle. Either {@code bundle} is given, or it
 *       is built from {@code memberId}, {@code patientFamily}, {@code patientGiven}, {@code patientBirthDate},
 *       {@code payerId}, {@code payerName}, {@code providerNpi}, {@code providerName}, {@code procedureCode}
 *       (CPT/HCPCS), {@code diagnosisCode} (ICD-10-CM), {@code quantity}, {@code priority} ({@code urgent} → expedited
 *       72 h clock; else standard 7 d), {@code questionnaireResponse} (DTR output, optional). The Claim carries the
 *       identifier {@code urn:sangam:operation|<operationId>}.</li>
 *   <li>{@code inquire} (PAS): {@code Claim/$inquire} by that identifier; also the {@link #lookup}.</li>
 *   <li>{@code cancel}: {@code Claim/$submit} of the same Claim with {@code status=cancelled}.</li>
 * </ul>
 * Outputs of submit / inquire: {@code outcome} (complete, queued, partial, error), {@code decision} (APPROVED, DENIED,
 * PENDED, MODIFIED, ERROR), {@code reviewAction} (X12 HCR01: A1, A3, A4, A6), {@code reasonCode} and
 * {@code reasonText} (the specific denial reason required by CMS-0057-F), {@code preAuthRef}, {@code claimResponse}.
 * A pended answer is completed later by a Subscription notification on ClaimResponse ({@code FhirSubscriptionReceiver}
 * → message {@code sangam.ushealth.pasUpdate}).
 *
 * <p>Class (b): a repeated {@code $submit} could open a second review at the payer, so a retry is preceded by
 * {@code $inquire}.
 */
public final class PasConnector implements SangamConnector {

    public static final String TYPE = "davinci-pas";
    public static final String EXT_REVIEW_ACTION = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-reviewAction";
    public static final String EXT_REVIEW_CODE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-reviewActionCode";
    public static final String PAS_PROFILE_CLAIM = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-claim";

    private final String id;
    private final FhirClient client;
    private final ConnectorDescriptor descriptor;

    public PasConnector(String id, FhirClient client, ConnectorProfile profile) {
        this.id = Objects.requireNonNull(id, "id");
        this.client = Objects.requireNonNull(client, "client");
        this.descriptor = new ConnectorDescriptor(id, TYPE, CapabilityClass.B_LOOKUP_ONLY,
                List.of("questionnairePackage", "submit", "inquire", "cancel"), profile);
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest r) {
        String rid = r.operationId().toString();
        long dl = HealthHttp.deadline(r);
        try {
            return switch (r.operation()) {
                case "questionnairePackage" -> {
                    FhirClient.Answer a = client.operation(r.tenantId(), "Questionnaire/$questionnaire-package",
                            dtrParameters(r), rid, dl, true);
                    if (!a.ok() || a.body() == null) {
                        yield HealthHttp.failure("DTR " + id, a.outcome(), true);
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("bundle", HealthHttp.map(a.body()));
                    java.util.List<Object> qs = new java.util.ArrayList<>();
                    collect(a.body(), "Questionnaire", qs);
                    out.put("questionnaires", qs);
                    out.put("questionnaireCount", qs.size());
                    yield ConnectorResult.Success.of(out, null);
                }
                case "submit" -> submit(r, buildOrGiven(r, "active"), rid, dl);
                case "cancel" -> submit(r, buildOrGiven(r, "cancelled"), rid, dl);
                case "inquire" -> {
                    FhirClient.Answer a = client.operation(r.tenantId(), "Claim/$inquire", inquiryBundle(r), rid, dl, true);
                    if (!a.ok() || a.body() == null) {
                        yield HealthHttp.failure("PAS " + id, a.outcome(), true);
                    }
                    Map<String, Object> out = interpret(a.body());
                    yield ConnectorResult.Success.of(out, String.valueOf(out.getOrDefault("preAuthRef", rid)));
                }
                default -> ConnectorResult.BusinessFailure.of("UNSUPPORTED_OPERATION", r.operation());
            };
        } catch (IllegalArgumentException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_INPUT", e.getMessage());
        }
    }

    @Override
    public LookupResult lookup(ConnectorRequest r) {
        if (!"submit".equals(r.operation()) && !"cancel".equals(r.operation())) {
            return new LookupResult.NotFound();
        }
        FhirClient.Answer a = client.operation(r.tenantId(), "Claim/$inquire", inquiryBundle(r),
                r.operationId().toString(), HealthHttp.deadline(r), true);
        if (a.status() == 404) {
            return new LookupResult.NotFound();
        }
        if (!a.ok() || a.body() == null) {
            return HealthHttp.lookupFailure("PAS " + id, a.outcome());
        }
        Map<String, Object> out = interpret(a.body());
        if ("NOT_FOUND".equals(out.get("decision"))) {
            return new LookupResult.NotFound();
        }
        return LookupResult.Found.success(out, String.valueOf(out.getOrDefault("preAuthRef", "")));
    }

    private ConnectorResult submit(ConnectorRequest r, ObjectNode bundle, String rid, long dl) {
        FhirClient.Answer a = client.operation(r.tenantId(), "Claim/$submit", bundle, rid, dl, false);
        if (!a.ok() || a.body() == null) {
            return HealthHttp.failure("PAS " + id, a.outcome(), false);
        }
        Map<String, Object> out = interpret(a.body());
        if ("ERROR".equals(out.get("decision"))) {
            return new ConnectorResult.BusinessFailure(String.valueOf(out.getOrDefault("reasonCode", "PAS_ERROR")),
                    String.valueOf(out.getOrDefault("reasonText", "PAS request in error")), out);
        }
        return ConnectorResult.Success.of(out, String.valueOf(out.getOrDefault("preAuthRef", rid)));
    }

    /** Reads the ClaimResponse in a PAS response Bundle (or a bare ClaimResponse). */
    public static Map<String, Object> interpret(JsonNode body) {
        JsonNode cr = "ClaimResponse".equals(body.path("resourceType").asText()) ? body : null;
        if (cr == null) {
            for (JsonNode e : body.path("entry")) {
                if ("ClaimResponse".equals(e.path("resource").path("resourceType").asText())) {
                    cr = e.path("resource");
                    break;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (cr == null) {
            out.put("decision", "NOT_FOUND");
            return out;
        }
        String outcome = cr.path("outcome").asText("");
        out.put("outcome", outcome);
        String action = null;
        String reason = null;
        String reasonText = null;
        for (JsonNode item : cr.path("item")) {
            for (JsonNode ext : item.path("extension")) {
                if (EXT_REVIEW_ACTION.equals(ext.path("url").asText())) {
                    for (JsonNode sub : ext.path("extension")) {
                        String u = sub.path("url").asText();
                        JsonNode coding = sub.path("valueCodeableConcept").path("coding").path(0);
                        if ("number".equals(u) && sub.has("valueString")) {
                            out.putIfAbsent("preAuthRef", sub.path("valueString").asText());
                        } else if ("reasonCode".equals(u)) {
                            reason = coding.path("code").asText(null);
                            reasonText = coding.path("display").asText(sub.path("valueCodeableConcept").path("text").asText(null));
                        } else if (EXT_REVIEW_CODE.equals(u) || "code".equals(u)) {
                            action = coding.path("code").asText(null);
                        }
                    }
                }
            }
            for (JsonNode adj : item.path("adjudication")) {
                if (reason == null && adj.has("reason")) {
                    reason = adj.path("reason").path("coding").path(0).path("code").asText(null);
                    reasonText = adj.path("reason").path("coding").path(0).path("display").asText(null);
                }
            }
        }
        if (cr.has("preAuthRef")) {
            out.put("preAuthRef", cr.path("preAuthRef").asText());
        }
        if (cr.has("disposition")) {
            out.put("disposition", cr.path("disposition").asText());
        }
        if (reason == null && cr.path("error").size() > 0) {
            reason = cr.path("error").path(0).path("code").path("coding").path(0).path("code").asText(null);
        }
        String decision = action == null ? switch (outcome) {
            case "queued" -> "PENDED";
            case "error" -> "ERROR";
            case "partial" -> "MODIFIED";
            default -> "UNKNOWN";
        } : switch (action) {
            case "A1" -> "APPROVED";
            case "A2", "A6" -> "MODIFIED";
            case "A3" -> "DENIED";
            case "A4" -> "PENDED";
            default -> "UNKNOWN";
        };
        out.put("decision", decision);
        if (action != null) {
            out.put("reviewAction", action);
        }
        if (reason != null) {
            out.put("reasonCode", reason);
        }
        if (reasonText != null) {
            out.put("reasonText", reasonText);
        }
        out.put("claimResponse", HealthHttp.map(cr));
        return HealthHttp.noNulls(out);
    }

    private ObjectNode buildOrGiven(ConnectorRequest r, String status) {
        Object given = r.input().get("bundle");
        ObjectNode bundle;
        if (given != null) {
            JsonNode n = given instanceof String s ? Json.tree(s) : Json.MAPPER.valueToTree(given);
            if (n == null || !n.isObject()) {
                throw new IllegalArgumentException("bundle must be a FHIR Bundle");
            }
            bundle = (ObjectNode) n.deepCopy();
        } else {
            bundle = build(r);
        }
        for (JsonNode e : bundle.path("entry")) {
            if (e.path("resource") instanceof ObjectNode res && "Claim".equals(res.path("resourceType").asText())) {
                res.put("status", status);
                tag(res, r.operationId().toString());
            }
        }
        return bundle;
    }

    private static void tag(ObjectNode claim, String op) {
        ArrayNode ids = claim.has("identifier") ? (ArrayNode) claim.get("identifier") : claim.putArray("identifier");
        for (JsonNode i : ids) {
            if (FhirConnector.OP_SYSTEM.equals(i.path("system").asText())) {
                return;
            }
        }
        ids.addObject().put("system", FhirConnector.OP_SYSTEM).put("value", op);
    }

    /** Minimal PAS request Bundle (Claim, Patient, Coverage, insurer and provider Organizations, optional QR). */
    static ObjectNode build(ConnectorRequest r) {
        ObjectNode b = Json.MAPPER.createObjectNode();
        b.put("resourceType", "Bundle");
        b.put("type", "collection");
        b.putObject("identifier").put("system", FhirConnector.OP_SYSTEM).put("value", r.operationId().toString());
        b.put("timestamp", java.time.Instant.now().toString());
        ArrayNode entries = b.putArray("entry");
        ObjectNode claim = Json.MAPPER.createObjectNode();
        claim.put("resourceType", "Claim");
        claim.put("id", "claim-1");
        claim.putObject("meta").putArray("profile").add(PAS_PROFILE_CLAIM);
        claim.put("status", "active");
        claim.putObject("type").putArray("coding").addObject()
                .put("system", "http://terminology.hl7.org/CodeSystem/claim-type").put("code", "professional");
        claim.put("use", "preauthorization");
        claim.putObject("patient").put("reference", "Patient/patient-1");
        claim.put("created", LocalDate.now(ZoneOffset.UTC).toString());
        claim.putObject("insurer").put("reference", "Organization/insurer-1");
        claim.putObject("provider").put("reference", "Organization/provider-1");
        boolean urgent = "urgent".equalsIgnoreCase(r.str("priority", "normal"))
                || "expedited".equalsIgnoreCase(r.str("priority", "normal"));
        claim.putObject("priority").putArray("coding").addObject()
                .put("system", "http://terminology.hl7.org/CodeSystem/processpriority").put("code", urgent ? "stat" : "normal");
        ObjectNode ins = claim.putArray("insurance").addObject();
        ins.put("sequence", 1).put("focal", true).putObject("coverage").put("reference", "Coverage/coverage-1");
        if (r.str("diagnosisCode") != null) {
            ObjectNode dx = claim.putArray("diagnosis").addObject();
            dx.put("sequence", 1);
            dx.putObject("diagnosisCodeableConcept").putArray("coding").addObject()
                    .put("system", "http://hl7.org/fhir/sid/icd-10-cm").put("code", r.str("diagnosisCode"));
        }
        ObjectNode item = claim.putArray("item").addObject();
        item.put("sequence", 1);
        item.putObject("productOrService").putArray("coding").addObject()
                .put("system", r.str("procedureSystem", "http://www.ama-assn.org/go/cpt")).put("code", r.required("procedureCode"));
        item.putObject("quantity").put("value", Integer.parseInt(r.str("quantity", "1")));
        if (r.str("serviceDate") != null) {
            item.put("servicedDate", r.str("serviceDate"));
        }
        if (r.input().get("questionnaireResponse") != null) {
            claim.putArray("supportingInfo").addObject().put("sequence", 1)
                    .putObject("valueReference").put("reference", "QuestionnaireResponse/qr-1");
        }
        entries.addObject().put("fullUrl", "urn:uuid:claim-1").set("resource", claim);

        ObjectNode patient = Json.MAPPER.createObjectNode();
        patient.put("resourceType", "Patient").put("id", "patient-1");
        patient.putArray("identifier").addObject().put("system", "urn:sangam:member").put("value", r.required("memberId"));
        ObjectNode name = patient.putArray("name").addObject();
        name.put("family", r.str("patientFamily", "UNKNOWN"));
        name.putArray("given").add(r.str("patientGiven", "UNKNOWN"));
        if (r.str("patientBirthDate") != null) {
            patient.put("birthDate", r.str("patientBirthDate"));
        }
        entries.addObject().put("fullUrl", "urn:uuid:patient-1").set("resource", patient);

        ObjectNode cov = Json.MAPPER.createObjectNode();
        cov.put("resourceType", "Coverage").put("id", "coverage-1").put("status", "active");
        cov.putObject("beneficiary").put("reference", "Patient/patient-1");
        cov.put("subscriberId", r.required("memberId"));
        cov.putArray("payor").addObject().put("reference", "Organization/insurer-1");
        entries.addObject().put("fullUrl", "urn:uuid:coverage-1").set("resource", cov);

        ObjectNode payer = Json.MAPPER.createObjectNode();
        payer.put("resourceType", "Organization").put("id", "insurer-1").put("name", r.str("payerName", "PAYER"));
        payer.putArray("identifier").addObject().put("system", "urn:sangam:payer-id").put("value", r.required("payerId"));
        entries.addObject().put("fullUrl", "urn:uuid:insurer-1").set("resource", payer);

        ObjectNode prov = Json.MAPPER.createObjectNode();
        prov.put("resourceType", "Organization").put("id", "provider-1").put("name", r.str("providerName", "PROVIDER"));
        prov.putArray("identifier").addObject().put("system", "http://hl7.org/fhir/sid/us-npi").put("value", r.required("providerNpi"));
        entries.addObject().put("fullUrl", "urn:uuid:provider-1").set("resource", prov);

        Object qr = r.input().get("questionnaireResponse");
        if (qr != null) {
            JsonNode q = qr instanceof String s ? Json.tree(s) : Json.MAPPER.valueToTree(qr);
            if (q instanceof ObjectNode qo) {
                qo.put("id", "qr-1");
                entries.addObject().put("fullUrl", "urn:uuid:qr-1").set("resource", qo);
            }
        }
        return b;
    }

    private static ObjectNode inquiryBundle(ConnectorRequest r) {
        ObjectNode b = Json.MAPPER.createObjectNode();
        b.put("resourceType", "Bundle");
        b.put("type", "collection");
        ObjectNode claim = b.putArray("entry").addObject().putObject("resource");
        claim.put("resourceType", "Claim");
        claim.put("status", "active");
        claim.put("use", "preauthorization");
        claim.putArray("identifier").addObject().put("system", FhirConnector.OP_SYSTEM)
                .put("value", r.str("originalOperationId", r.operationId().toString()));
        return b;
    }

    private static ObjectNode dtrParameters(ConnectorRequest r) {
        ObjectNode p = Json.MAPPER.createObjectNode();
        p.put("resourceType", "Parameters");
        ArrayNode ps = p.putArray("parameter");
        if (r.str("coverageId") != null) {
            ps.addObject().put("name", "coverage").putObject("valueReference").put("reference", "Coverage/" + r.str("coverageId"));
        }
        if (r.str("procedureCode") != null) {
            ObjectNode order = ps.addObject().put("name", "order").putObject("resource");
            order.put("resourceType", "ServiceRequest").put("status", "draft").put("intent", "order");
            order.putObject("code").putArray("coding").addObject()
                    .put("system", r.str("procedureSystem", "http://www.ama-assn.org/go/cpt")).put("code", r.str("procedureCode"));
            order.putObject("subject").put("reference", "Patient/" + r.str("patientId", "patient-1"));
        }
        for (String q : r.str("questionnaireUrls", "").split(",")) {
            if (!q.isBlank()) {
                ps.addObject().put("name", "questionnaire").put("valueCanonical", q.trim());
            }
        }
        return p;
    }

    private static void collect(JsonNode n, String type, java.util.List<Object> out) {
        if (n.isObject()) {
            if (type.equals(n.path("resourceType").asText())) {
                out.add(HealthHttp.map(n));
                return;
            }
            n.fields().forEachRemaining(e -> collect(e.getValue(), type, out));
        } else if (n.isArray()) {
            n.forEach(c -> collect(c, type, out));
        }
    }
}
