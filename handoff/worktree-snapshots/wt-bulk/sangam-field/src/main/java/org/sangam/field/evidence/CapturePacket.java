// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.evidence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * N05 {@code CapturePacket}: one unit of offline work uploaded by a device. The device computes {@code packetHash} =
 * SHA-256 over the canonical form of every field except {@code packetHash} and {@code signature}
 * ({@link PacketHasher}); {@code prevHash} chains it to the device's previous packet, so a deleted, reordered or edited
 * packet breaks the chain (tamper evidence). {@code signature} is optional: SHA256withECDSA (P-256) over the hash hex
 * with the device key registered at enrolment.
 *
 * @param type        VISIT_SUBMIT, EVIDENCE_META, DISPATCH_STATUS, POSITION_PING, ASSIGNMENT_ACK
 * @param deviceSeq   strictly increasing per device (gaps are reported, not fatal)
 * @param baseVersion assignment version the device captured against (conflict detection)
 * @param payload     type-specific data (dispatch status, ping, ack) — capture data goes in answers/media
 */
public record CapturePacket(String packetId, String type, String operationId, String deviceId, long deviceSeq, String appVersion,
                            String assignmentId, Integer baseVersion, Instant deviceTime, Map<String, Object> answers,
                            String notes, List<MediaRef> media, GpsFix fix, Map<String, Object> payload, String prevHash,
                            String packetHash, String signature) {

    public record GpsFix(double lat, double lon, Double accuracyM, Double altitudeM, String provider, boolean mockLocation,
                         Instant fixTime) {
    }

    /** A photo / document / signature referenced by the packet; the bytes are uploaded separately and hash-matched. */
    public record MediaRef(String evidenceId, String itemId, String sha256, String mediaType, Long sizeBytes, Instant capturedAt,
                           GpsFix fix) {
    }

    public CapturePacket {
        answers = answers == null ? Map.of() : answers;
        media = media == null ? List.of() : List.copyOf(media);
        payload = payload == null ? Map.of() : payload;
    }

    /** The fields covered by the packet hash, in a stable map. */
    public Map<String, Object> hashedFields() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("packetId", packetId);
        m.put("type", type);
        m.put("operationId", operationId);
        m.put("deviceId", deviceId);
        m.put("deviceSeq", deviceSeq);
        m.put("appVersion", appVersion);
        m.put("assignmentId", assignmentId);
        m.put("baseVersion", baseVersion);
        m.put("deviceTime", deviceTime);
        m.put("answers", answers);
        m.put("notes", notes);
        m.put("media", media);
        m.put("fix", fix);
        m.put("payload", payload);
        m.put("prevHash", prevHash);
        return m;
    }
}
