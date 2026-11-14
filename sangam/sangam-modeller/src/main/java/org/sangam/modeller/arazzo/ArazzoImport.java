// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.arazzo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The result of one Arazzo import (M-069, owner decision 159): the BPMN draft, the diagnostics report and the API
 * contracts the draft refers to. A draft always needs a human review before it is deployed (the package deploy pins
 * every {@code sangam:operationRef}, M-068); a document with an unsupported construct is not converted at all.
 * No conformance with the Arazzo specification is claimed.
 *
 * @param resource    the Arazzo resource name
 * @param status      {@link Status#REVIEW_REQUIRED} with a BPMN draft, or {@link Status#MANUAL} without one
 * @param bpmn        the BPMN 2.0 draft (UTF-8, no diagram interchange), null when {@link Status#MANUAL}
 * @param diagnostics every finding in document order: errors (not converted), warnings (converted, behaviour to
 *                    review) and notes
 * @param contracts   the source descriptions the operation tasks refer to, each with the contract id the package
 *                    must store the API document under
 * @param processes   workflow id to BPMN process id, in document order (empty when {@link Status#MANUAL})
 */
public record ArazzoImport(String resource, Status status, byte[] bpmn, List<Diagnostic> diagnostics,
                           List<ContractRef> contracts, Map<String, String> processes) {

    /** Outcome of an import. */
    public enum Status {
        /** Every construct is in the supported subset: a BPMN draft exists and must be reviewed before deployment. */
        REVIEW_REQUIRED,
        /** At least one construct is not converted: no BPMN; model it by hand. */
        MANUAL
    }

    /** How serious a finding is. */
    public enum Severity { ERROR, WARNING, INFO }

    /**
     * One finding.
     *
     * @param severity ERROR: not converted, so no draft; WARNING: converted, but behaviour differs or was left to the
     *                 runtime and must be reviewed; INFO: a note
     * @param code     stable code ({@code ARAZZO-UNSUPPORTED-RETRY}, ...)
     * @param location a JSON pointer into the Arazzo document (empty for the whole document)
     * @param message  what was found and what to do
     */
    public record Diagnostic(Severity severity, String code, String location, String message) {
        public Diagnostic {
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(code, "code");
            location = location == null ? "" : location;
            message = message == null ? "" : message;
        }
    }

    /**
     * One source description of the document.
     *
     * @param sourceName the Arazzo {@code sourceDescriptions[].name}
     * @param contractId the contract id used in {@code sangam:operationRef} (the API contract store id, M-068)
     * @param url        the source URL as written (never fetched)
     * @param type       {@code openapi} or {@code arazzo}
     */
    public record ContractRef(String sourceName, String contractId, String url, String type) {
    }

    public ArazzoImport {
        Objects.requireNonNull(status, "status");
        diagnostics = List.copyOf(diagnostics);
        contracts = List.copyOf(contracts);
        processes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(processes));
        if (status == Status.MANUAL && bpmn != null) {
            throw new IllegalArgumentException("a MANUAL import has no BPMN");
        }
        if (status == Status.REVIEW_REQUIRED && bpmn == null) {
            throw new IllegalArgumentException("a REVIEW_REQUIRED import has a BPMN draft");
        }
        bpmn = bpmn == null ? null : bpmn.clone();
    }

    @Override
    public byte[] bpmn() {
        return bpmn == null ? null : bpmn.clone();
    }

    public boolean converted() {
        return status == Status.REVIEW_REQUIRED;
    }

    public List<Diagnostic> errors() {
        return diagnostics.stream().filter(d -> d.severity() == Severity.ERROR).toList();
    }

    /** The report as a JSON-ready map; the BPMN is included as text when converted. */
    public Map<String, Object> toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resource", resource);
        out.put("status", status.name());
        List<Map<String, Object>> d = new ArrayList<>();
        for (Diagnostic x : diagnostics) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", x.severity().name());
            m.put("code", x.code());
            m.put("location", x.location());
            m.put("message", x.message());
            d.add(m);
        }
        out.put("diagnostics", d);
        List<Map<String, Object>> c = new ArrayList<>();
        for (ContractRef x : contracts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sourceName", x.sourceName());
            m.put("contractId", x.contractId());
            m.put("url", x.url());
            m.put("type", x.type());
            c.add(m);
        }
        out.put("contracts", c);
        out.put("processes", processes);
        out.put("bpmn", bpmn == null ? null : new String(bpmn, StandardCharsets.UTF_8));
        return out;
    }
}
