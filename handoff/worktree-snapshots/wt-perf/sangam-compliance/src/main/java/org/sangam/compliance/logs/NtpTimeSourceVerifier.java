// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.logs;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Clock-synchronisation evidence. An SNTP (RFC 4330) client in plain JDK code queries each configured server (the
 * pack supplies defaults; IN: {@code samay1.nic.in}, {@code samay2.nic.in}, {@code time.nplindia.org} per CERT-In
 * para (i), CMP-085, CMP-086, CMP-106) and measures the offset of the JVM clock and the database clock (A-1). Each
 * check is stored in {@code CMP_NTP_CHECK} and audited. Disabled until {@code ntp.enabled=true} (air-gapped default).
 */
public final class NtpTimeSourceVerifier {

    public record ServerResult(String server, boolean reachable, int stratum, long offsetMs, long roundTripMs, String error) {
    }

    public record Check(String checkId, Instant checkedAt, boolean ok, long jvmOffsetMs, long dbOffsetMs, String bestServer,
                        List<ServerResult> servers, String detail) {
    }

    /** SNTP transport, replaceable for tests or for an approved internal time API. */
    @FunctionalInterface
    public interface SntpClient {
        ServerResult query(String server, int timeoutMs);
    }

    private static final long NTP_EPOCH_OFFSET_SECONDS = 2_208_988_800L;

    private final Dialect dialect;
    private final ComplianceAudit audit;
    private final ComplianceConfig.Provider config;
    private final SntpClient client;

    public NtpTimeSourceVerifier(Dialect dialect, ComplianceAudit audit, ComplianceConfig.Provider config, SntpClient client) {
        this.dialect = dialect;
        this.audit = audit;
        this.config = config;
        this.client = client == null ? NtpTimeSourceVerifier::sntp : client;
    }

    /** Runs a check and records it; returns empty when NTP verification is disabled for the tenant. */
    public Optional<Check> check(Connection c, String tenantId) {
        ComplianceConfig cfg = config.forTenant(tenantId);
        if (!cfg.ntpEnabled) {
            return Optional.empty();
        }
        List<ServerResult> results = new ArrayList<>();
        for (String s : cfg.ntpServers) {
            results.add(client.query(s, cfg.ntpTimeoutMs));
        }
        Optional<ServerResult> best = results.stream().filter(r -> r.reachable() && r.stratum() >= 1 && r.stratum() <= 15)
                .min((a, b) -> Long.compare(a.roundTripMs(), b.roundTripMs()));
        long jvmOffset = best.map(ServerResult::offsetMs).orElse(Long.MAX_VALUE);
        // database clock vs the reference: dbNow - (jvmNow + jvmOffset)
        Instant jvmNow = Instant.now();
        Instant dbNow = Jdbc.dbNow(c, dialect);
        long dbOffset = best.isPresent() ? Duration.between(jvmNow.plusMillis(jvmOffset), dbNow).toMillis() : Long.MAX_VALUE;
        boolean ok = best.isPresent() && Math.abs(jvmOffset) <= cfg.ntpMaxOffsetMs && Math.abs(dbOffset) <= cfg.ntpMaxOffsetMs + 1000;
        String detail = best.isEmpty() ? "no configured NTP server answered" : ok ? "within tolerance"
                : "offset exceeds " + cfg.ntpMaxOffsetMs + " ms";
        String id = Sql.newId();
        Instant now = Sql.now(c, dialect);
        Jdbc.update(c, "INSERT INTO CMP_NTP_CHECK(tenant_id, check_id, checked_at, ok, jvm_offset_ms, db_offset_ms, best_server,"
                        + " results_json, detail) VALUES (?,?,?,?,?,?,?,?,?)",
                tenantId, id, now, ok, best.isPresent() ? jvmOffset : null, best.isPresent() ? dbOffset : null,
                best.map(ServerResult::server).orElse(null), Sql.json(results.stream().map(NtpTimeSourceVerifier::asMap).toList()), detail);
        audit.organisation(c, tenantId, ComplianceAudit.NTP_CHECKED, Sql.kv("check", id, "ok", ok,
                "server", best.map(ServerResult::server).orElse(null), "jvmOffsetMs", best.isPresent() ? jvmOffset : null,
                "dbOffsetMs", best.isPresent() ? dbOffset : null));
        return Optional.of(new Check(id, now, ok, jvmOffset, dbOffset, best.map(ServerResult::server).orElse(null), results, detail));
    }

    public Optional<Map<String, Object>> latest(Connection c, String tenantId) {
        return Jdbc.query(c, "SELECT check_id, checked_at, ok, jvm_offset_ms, db_offset_ms, best_server, detail FROM CMP_NTP_CHECK"
                        + " WHERE tenant_id=? ORDER BY checked_at DESC",
                rs -> Sql.kv("checkId", rs.getString(1), "checkedAt", String.valueOf(Jdbc.instant(rs, "checked_at")),
                        "ok", Sql.bool(rs.getInt(3)), "jvmOffsetMs", rs.getObject(4) == null ? null : rs.getLong(4),
                        "dbOffsetMs", rs.getObject(5) == null ? null : rs.getLong(5), "server", rs.getString(6), "detail", rs.getString(7)),
                tenantId).stream().findFirst();
    }

    private static Map<String, Object> asMap(ServerResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("server", r.server());
        m.put("reachable", r.reachable());
        m.put("stratum", r.stratum());
        m.put("offsetMs", r.offsetMs());
        m.put("roundTripMs", r.roundTripMs());
        m.put("error", r.error());
        return m;
    }

    /** RFC 4330 SNTP v4 client request (mode 3) and offset/round-trip computation. */
    static ServerResult sntp(String server, int timeoutMs) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);
            InetAddress addr = InetAddress.getByName(server);
            byte[] buf = new byte[48];
            buf[0] = 0x23; // LI = 0, VN = 4, Mode = 3 (client)
            long t1 = System.currentTimeMillis();
            writeTimestamp(buf, 40, t1);
            socket.send(new DatagramPacket(buf, buf.length, addr, 123));
            DatagramPacket resp = new DatagramPacket(new byte[48], 48);
            socket.receive(resp);
            long t4 = System.currentTimeMillis();
            byte[] r = resp.getData();
            int mode = r[0] & 0x7;
            int stratum = r[1] & 0xff;
            if (mode != 4 && mode != 5) {
                return new ServerResult(server, false, stratum, 0, t4 - t1, "unexpected mode " + mode);
            }
            long t2 = readTimestamp(r, 32);
            long t3 = readTimestamp(r, 40);
            long offset = ((t2 - t1) + (t3 - t4)) / 2;
            long rtt = (t4 - t1) - (t3 - t2);
            return new ServerResult(server, true, stratum, offset, rtt, null);
        } catch (IOException | RuntimeException e) {
            return new ServerResult(server, false, 0, 0, 0, e.getClass().getSimpleName());
        }
    }

    static long readTimestamp(byte[] b, int off) {
        long seconds = 0;
        for (int i = 0; i < 4; i++) {
            seconds = (seconds << 8) | (b[off + i] & 0xffL);
        }
        long fraction = 0;
        for (int i = 4; i < 8; i++) {
            fraction = (fraction << 8) | (b[off + i] & 0xffL);
        }
        return (seconds - NTP_EPOCH_OFFSET_SECONDS) * 1000L + (fraction * 1000L >>> 32);
    }

    static void writeTimestamp(byte[] b, int off, long millis) {
        long seconds = millis / 1000L + NTP_EPOCH_OFFSET_SECONDS;
        long fraction = ((millis % 1000L) << 32) / 1000L;
        for (int i = 3; i >= 0; i--) {
            b[off + i] = (byte) (seconds & 0xff);
            seconds >>>= 8;
        }
        for (int i = 7; i >= 4; i--) {
            b[off + i] = (byte) (fraction & 0xff);
            fraction >>>= 8;
        }
    }
}
