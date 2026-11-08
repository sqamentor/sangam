// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.packs;

import org.sangam.integration.connector.Json;
import org.sangam.integration.inbox.ChannelBinding;
import org.sangam.integration.inbox.InboundMessage;
import org.sangam.integration.inbox.InboxService;
import org.sangam.packs.port.ConnectorGateway;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Delivers the pack message contract (sangam-packs README §4) to waiting process instances through the inbox, so a
 * result that arrives before the instance waits is buffered (FLOWABLE-INTO-OPERATON D-33) and a redelivery is deduplicated (R38).
 *
 * <table>
 *   <caption>Messages and variables</caption>
 *   <tr><th>Message</th><th>Variables</th><th>Correlated on (first variable that matches)</th></tr>
 *   <tr><td>{@value ConnectorGateway#MSG_OPERATION_RESULT}</td><td>resultStatus, resultReasonCode, resultExternalRef</td><td>operationId</td></tr>
 *   <tr><td>{@value ConnectorGateway#MSG_RECONCILIATION_RESOLVED}</td><td>resolution, resolutionEvidence, resolvedBy (+ resolutionReasonCode)</td><td>operationId</td></tr>
 *   <tr><td>{@value ConnectorGateway#MSG_CANCEL_ACK}</td><td>cancelResult</td><td>openOperationId, operationId</td></tr>
 *   <tr><td>{@value ConnectorGateway#MSG_SIGNATURE_RESULT}</td><td>signatureResult (+ signatureCertSerial, signatureValue)</td><td>signatureOperationId, operationId</td></tr>
 * </table>
 * The message id is {@code <message>:<operationId>:<generation>[:<suffix>]}, stable per logical result.
 */
public final class PackMessages {

    public static final String SOURCE = "sangam:packs";

    /** Correlation variables per message, in order of preference. */
    public static final Map<String, List<String>> CORRELATION_VARIABLES = Map.of(
            ConnectorGateway.MSG_OPERATION_RESULT, List.of("operationId"),
            ConnectorGateway.MSG_RECONCILIATION_RESOLVED, List.of("operationId"),
            ConnectorGateway.MSG_CANCEL_ACK, List.of("openOperationId", "operationId"),
            ConnectorGateway.MSG_SIGNATURE_RESULT, List.of("signatureOperationId", "operationId"));

    private final InboxService inbox;

    public PackMessages(InboxService inbox) {
        this.inbox = inbox;
    }

    public void operationResult(PackOperationStore.Row op, String status, String reasonCode, String externalRef) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("resultStatus", status);
        v.put("resultReasonCode", reasonCode);
        v.put("resultExternalRef", externalRef);
        deliver(op, ConnectorGateway.MSG_OPERATION_RESULT, v, status);
    }

    public void signatureResult(PackOperationStore.Row op, String status, Map<String, Object> output, String reasonCode) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("signatureResult", status);
        v.put("signatureReasonCode", reasonCode);
        Object serial = output == null ? null : output.get("certSerial");
        if (serial != null) {
            v.put("signatureCertSerial", String.valueOf(serial));
        }
        Object sig = output == null ? null : output.get("signature");
        if (sig != null) {
            v.put("signatureValue", String.valueOf(sig));
        }
        deliver(op, ConnectorGateway.MSG_SIGNATURE_RESULT, v, status);
    }

    public void cancelAck(PackOperationStore.Row op, String cancelResult) {
        deliver(op, ConnectorGateway.MSG_CANCEL_ACK, Map.of("cancelResult", cancelResult), cancelResult);
    }

    public void reconciliationResolved(PackOperationStore.Row op, String resolution, String evidence, String resolvedBy,
                                       String reasonCode) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("resolution", resolution);
        v.put("resolutionEvidence", evidence);
        v.put("resolvedBy", resolvedBy);
        if (reasonCode != null) {
            v.put("resolutionReasonCode", reasonCode);
        }
        deliver(op, ConnectorGateway.MSG_RECONCILIATION_RESOLVED, v, resolution);
    }

    private void deliver(PackOperationStore.Row op, String message, Map<String, Object> variables, String suffix) {
        Map<String, Object> data = new LinkedHashMap<>(variables);
        data.put("_processInstanceId", op.processInstanceId());
        String id = message + ":" + op.operationId() + ":" + op.generation() + ":" + suffix;
        inbox.receive(ChannelBinding.of(SOURCE, op.tenantId(), "system:integration"),
                new InboundMessage(id.length() > 255 ? id.substring(0, 255) : id, message, op.operationId(), op.tenantId(),
                        Json.write(data)));
    }
}
