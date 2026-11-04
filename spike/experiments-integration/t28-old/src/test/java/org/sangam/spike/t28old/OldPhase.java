package org.sangam.spike.t28old;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;

/** Shared parts of the old phase: settings, WF-1 variables, schema versions, the state file for the new phase. */
public final class OldPhase {

    public static final String TENANT = "tn-t28";
    public static final String[] WF1_FILES = {"wf1-bill.bpmn", "wf1-delegation-of-powers.dmn"};

    private OldPhase() {
    }

    public static String jdbcUrl() {
        return required("t28.jdbcUrl");
    }

    public static String user() {
        return required("t28.user");
    }

    public static String password() {
        return required("t28.password");
    }

    static String required(String key) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("-D" + key + " is required");
        }
        return v;
    }

    /** Same variables as experiments-integration Workflows.wf1Vars (amount 50,000 routes to SECTION_OFFICER). */
    public static Map<String, Object> wf1Vars(String billId) {
        Map<String, Object> v = new HashMap<>();
        v.put("billId", billId);
        v.put("amount", 50_000L);
        v.put("beneficiary", "Synthetic Supplier " + billId);
        v.put("beneficiaryAccount", "ACC-" + billId);
        v.put("budgetHead", "2059-MAINT");
        v.put("initiatorSeat", "seat-initiator");
        v.put("checkerGroup", "seat-checker");
        return v;
    }

    public static Map<String, String> schemaProperties() {
        Map<String, String> out = new java.util.TreeMap<>();
        try (Connection c = DriverManager.getConnection(jdbcUrl(), user(), password()); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT NAME_, VALUE_ FROM ACT_GE_PROPERTY WHERE lower(NAME_) LIKE '%version%'")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    /** Writes the state file the new phase reads (T28UpgradeTest). */
    public static void writeState(String engine, String oldVersion, Map<String, String> instances, Map<String, String> extra)
            throws IOException {
        Properties p = new Properties();
        p.setProperty("engine", engine);
        p.setProperty("oldVersion", oldVersion);
        p.setProperty("jdbcUrl", jdbcUrl());
        p.setProperty("user", user());
        p.setProperty("password", password());
        p.setProperty("tenant", TENANT);
        instances.forEach((k, v) -> p.setProperty("instance." + k, v));
        extra.forEach(p::setProperty);
        schemaProperties().forEach((k, v) -> p.setProperty("schemaOld." + k, v));
        Path file = Path.of(required("t28.stateFile"));
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            p.store(w, "T28 old phase: " + engine + " " + oldVersion);
        }
    }

    /** Windows JVMs report India as Asia/Calcutta, which postgres:17 rejects at connect. */
    public static void canonicaliseDefaultTimeZone() {
        Map<String, String> legacy = Map.of("Asia/Calcutta", "Asia/Kolkata", "Asia/Katmandu", "Asia/Kathmandu",
                "Asia/Saigon", "Asia/Ho_Chi_Minh", "Asia/Rangoon", "Asia/Yangon", "Europe/Kiev", "Europe/Kyiv");
        String canonical = legacy.get(TimeZone.getDefault().getID());
        if (canonical != null) {
            TimeZone.setDefault(TimeZone.getTimeZone(canonical));
        }
    }
}
