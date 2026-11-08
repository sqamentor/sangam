// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.common;

import org.sangam.casefile.config.CaseConfig;
import org.sangam.casefile.support.Db;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gap-free human-readable numbers for cases, references and meetings (N09 number series), from the configured pattern.
 * The counter row is locked with the dialect's blocking row lock for the rest of the caller's transaction, so numbers
 * never repeat and a rolled-back transaction does not burn one.
 */
public class NumberService {

    private static final Pattern SEQ = Pattern.compile("\\{seq(?::(\\d+))?}");

    private final Dialect dialect;
    private final ZoneId zone;

    public NumberService(Dialect dialect, ZoneId zone) {
        this.dialect = dialect;
        this.zone = zone == null ? ZoneId.of("UTC") : zone;
    }

    public String next(Connection c, String tenant, CaseConfig.Numbering n, String orgCode, Instant at) {
        String t = Db.tenant(tenant);
        ZonedDateTime z = at.atZone(zone);
        String period = switch (n.reset()) {
            case "NEVER" -> "-";
            case "MONTHLY" -> String.format("%04d-%02d", z.getYear(), z.getMonthValue());
            default -> String.valueOf(z.getYear());
        };
        Jdbc.insertIfAbsent(c, dialect, "INSERT INTO CF_NUMBER_SEQ(tenant_id, series_key, period_key, next_value) VALUES (?,?,?,1)",
                t, n.series(), period);
        long value = Jdbc.one(c, dialect.lockingSelect("next_value", "CF_NUMBER_SEQ", "tenant_id=? AND series_key=? AND period_key=?"),
                rs -> rs.getLong("next_value"), t, n.series(), period).orElseThrow();
        Jdbc.update(c, "UPDATE CF_NUMBER_SEQ SET next_value=? WHERE tenant_id=? AND series_key=? AND period_key=?",
                value + 1, t, n.series(), period);
        String out = n.pattern()
                .replace("{prefix}", n.prefix() == null ? "" : n.prefix())
                .replace("{yyyy}", String.format("%04d", z.getYear()))
                .replace("{yy}", String.format("%02d", z.getYear() % 100))
                .replace("{mm}", String.format("%02d", z.getMonthValue()))
                .replace("{org}", orgCode == null ? "" : orgCode);
        Matcher m = SEQ.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int width = m.group(1) == null ? 1 : Integer.parseInt(m.group(1));
            m.appendReplacement(sb, String.format("%0" + Math.max(1, width) + "d", value));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
