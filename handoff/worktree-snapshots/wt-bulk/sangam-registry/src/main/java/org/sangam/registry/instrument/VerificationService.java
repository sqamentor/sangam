// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.instrument;

import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.RegistryPorts;
import org.sangam.registry.kernel.ObjectTypeDefinition;
import org.sangam.registry.kernel.RegisteredObject;
import org.sangam.registry.kernel.RegisteredObjectService;
import org.sangam.registry.support.Codes;
import org.sangam.registry.support.Db;
import org.sangam.registry.support.Json;
import org.sangam.registry.support.Maps;
import org.sangam.registry.support.RegistryAudit;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Public verification of issued instruments (N06 "public verification by number or QR, using a signed, minimal
 * payload with masking").
 *
 * <ul>
 *   <li><b>Verification code</b>: random, checksummed, stored only as a hash; a duplicate issue or holder change
 *       supersedes the old code.</li>
 *   <li><b>QR token</b>: {@code SGV1.<payload>.<signature>.<keyId>} (base64url), where the payload is the minimal
 *       canonical JSON {@code {v, tn, id, no, ty, vf, vt, iat}}: no personal data. An offline verifier checks the
 *       signature with the published key; an online check also reports the current status, because an instrument
 *       can be suspended or revoked after its QR was printed.</li>
 *   <li><b>Public view</b>: number, type title, status, validity, the type's public fields and, if configured, the
 *       holder's display name, all passed through the privacy port.</li>
 * </ul>
 * Verification reads never write the audit chain (high volume); they are counted through the metrics port.
 */
public final class VerificationService {

    public static final String QR_PREFIX = "SGV1";

    /** Result of a verification. {@code status}: VALID, SUSPENDED, EXPIRED, REVOKED, SURRENDERED, TRANSFERRED,
     * SUPERSEDED (an older code), NOT_FOUND, MALFORMED, INVALID_SIGNATURE, or the object's state otherwise. */
    public record Result(boolean valid, String status, Map<String, Object> view, Instant checkedAt) {
    }

    private final Dialect dialect;
    private final RegisteredObjectService objects;
    private final RegistryAudit audit;
    private final RegistryPorts ports;

    public VerificationService(Dialect dialect, RegisteredObjectService objects, RegistryAudit audit, RegistryPorts ports) {
        this.dialect = dialect;
        this.objects = objects;
        this.audit = audit;
        this.ports = ports;
    }

    /** Issues a new verification code for a target; the target's previous ACTIVE codes become SUPERSEDED. */
    public String issueCode(Connection c, RegistryContext ctx, String targetKind, String targetId) {
        Instant now = Db.now(c, dialect);
        Jdbc.update(c, "UPDATE REG_VERIFICATION SET status='SUPERSEDED', superseded_at=? WHERE tenant_id=? AND target_id=?"
                + " AND target_kind=? AND status='ACTIVE'", now, ctx.tenant(), targetId, targetKind);
        String code = Codes.newCode();
        Jdbc.update(c, "INSERT INTO REG_VERIFICATION(code_hash, tenant_id, target_kind, target_id, status, issued_at)"
                + " VALUES (?,?,?,?,?,?)", Codes.hash(code), ctx.tenant(), targetKind, targetId, "ACTIVE", now);
        return code;
    }

    /** Builds the signed QR token of an instrument. */
    public String qrToken(Connection c, RegistryContext ctx, RegisteredObject o) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("v", 1);
        p.put("tn", o.tenant());
        p.put("id", o.id());
        p.put("no", o.number());
        p.put("ty", o.typeKey());
        p.put("vf", o.validFrom() == null ? null : o.validFrom().toString());
        p.put("vt", o.validTo() == null ? null : o.validTo().toString());
        p.put("iat", Db.now(c, dialect).toString());
        byte[] payload = CanonicalJson.write(p).getBytes(StandardCharsets.UTF_8);
        RegistryPorts.Signature sig = ports.signer().sign(o.tenant(), "instrument-qr", payload);
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return QR_PREFIX + "." + b64.encodeToString(payload) + "." + b64.encodeToString(sig.value()) + "."
                + b64.encodeToString((sig.algorithm() + ":" + sig.keyId()).getBytes(StandardCharsets.UTF_8));
    }

    /** Parsed and signature-checked QR payload, or empty when malformed or not authentic. */
    public Optional<Map<String, Object>> parseQr(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String[] parts = token.trim().split("\\.");
        if (parts.length != 4 || !QR_PREFIX.equals(parts[0])) {
            return Optional.empty();
        }
        try {
            Base64.Decoder d = Base64.getUrlDecoder();
            byte[] payload = d.decode(parts[1]);
            byte[] sig = d.decode(parts[2]);
            String[] key = new String(d.decode(parts[3]), StandardCharsets.UTF_8).split(":", 2);
            Map<String, Object> p = Json.map(new String(payload, StandardCharsets.UTF_8));
            String tenant = Maps.str(p, "tn");
            if (tenant == null || key.length != 2 || !ports.signer().verify(tenant, key[1], key[0], payload, sig)) {
                return Optional.empty();
            }
            return Optional.of(p);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Online verification by code (the tenant comes from the verification portal's host or path). */
    public Result verifyCode(Connection c, RegistryContext ctx, String code) {
        Instant now = Db.now(c, dialect);
        if (!Codes.wellFormed(code)) {
            return count(new Result(false, "MALFORMED", Map.of(), now));
        }
        var row = Jdbc.one(c, "SELECT target_id, status, target_kind FROM REG_VERIFICATION WHERE code_hash=? AND tenant_id=?",
                rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, Codes.hash(code), ctx.tenant());
        if (row.isEmpty() || !"INSTRUMENT".equals(row.get()[2])) {
            return count(new Result(false, "NOT_FOUND", Map.of(), now));
        }
        Result r = status(c, ctx, row.get()[0], now);
        if ("SUPERSEDED".equals(row.get()[1])) {
            return count(new Result(false, "SUPERSEDED", Maps.of("number", r.view().get("number"),
                    "note", "this code was replaced; ask the holder for the current document"), now));
        }
        return count(r);
    }

    /** Online verification of a QR token: authenticity, then the current status. */
    public Result verifyQr(Connection c, String token) {
        Optional<Map<String, Object>> p = parseQr(token);
        if (p.isEmpty()) {
            return count(new Result(false, "INVALID_SIGNATURE", Map.of(), Instant.now()));
        }
        RegistryContext ctx = RegistryContext.of(Maps.str(p.get(), "tn"), "system:public-verification")
                .withPurpose("PUBLIC_VERIFICATION");
        Instant now = Db.now(c, dialect);
        Result r = status(c, ctx, Maps.str(p.get(), "id"), now);
        if (r.valid() && !java.util.Objects.equals(Maps.str(p.get(), "vt"), Maps.str(r.view(), "validTo"))) {
            // a renewal changed the validity after this QR was printed: still the same instrument, report as valid
            Map<String, Object> v = new LinkedHashMap<>(r.view());
            v.put("note", "validity changed since this code was printed");
            r = new Result(true, r.status(), v, now);
        }
        return count(r);
    }

    private Result status(Connection c, RegistryContext ctx, String objectId, Instant now) {
        RegisteredObject o;
        try {
            o = objects.get(c, ctx, objectId);
        } catch (org.sangam.registry.RegistryException e) {
            return new Result(false, "NOT_FOUND", Map.of(), now);
        }
        ObjectTypeDefinition type = objects.typeOf(c, o);
        boolean inForce = type.lifecycle().live(o.state())
                && (o.validFrom() == null || !now.isBefore(o.validFrom()))
                && (o.validTo() == null || now.isBefore(o.validTo()));
        String status = inForce ? "VALID"
                : type.lifecycle().live(o.state()) && o.validTo() != null && !now.isBefore(o.validTo()) ? "EXPIRED"
                : type.lifecycle().live(o.state()) ? "NOT_YET_VALID" : o.state();
        return new Result(inForce, status, publicView(c, ctx, o, type), now);
    }

    /** The masked public view of an instrument. */
    public Map<String, Object> publicView(Connection c, RegistryContext ctx, RegisteredObject o, ObjectTypeDefinition type) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("number", o.number());
        v.put("type", type.title());
        v.put("state", o.state());
        v.put("validFrom", o.validFrom() == null ? null : o.validFrom().toString());
        v.put("validTo", o.validTo() == null ? null : o.validTo().toString());
        Map<String, Object> pub = new LinkedHashMap<>();
        List<String> fields = type.verification().publicFields();
        for (String f : fields) {
            if (o.attrs().containsKey(f)) {
                pub.put(f, o.attrs().get(f));
            }
        }
        v.put("details", pub);
        if (type.verification().showHolderName() && o.holderPartyId() != null) {
            Jdbc.one(c, "SELECT display_name FROM REG_PARTY WHERE id=? AND tenant_id=?", rs -> rs.getString(1),
                    o.holderPartyId(), o.tenant()).ifPresent(n -> v.put("holderName", n));
        }
        List<String> sensitive = new java.util.ArrayList<>(type.schema().sensitiveFields());
        sensitive.add("holderName");
        return ports.privacy().mask(ctx, "registry-instrument-public", v, sensitive);
    }

    private Result count(Result r) {
        ports.metrics().increment("registry.instrument.verified", Map.of("status", r.status()));
        return r;
    }

    RegistryAudit audit() {
        return audit;
    }
}
