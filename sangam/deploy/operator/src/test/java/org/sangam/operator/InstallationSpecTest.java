// SPDX-License-Identifier: Apache-2.0
package org.sangam.operator;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class InstallationSpecTest {

    static JsonNode cr(String name, String spec) throws Exception {
        return FakeKube.JSON.readTree("{\"metadata\":{\"name\":\"" + name + "\",\"namespace\":\"n\",\"generation\":3},\"spec\":" + spec + "}");
    }

    static final String DB = "\"database\":{\"type\":\"oracle\",\"jdbcUrl\":\"jdbc:oracle:thin:@db:1521/S\",\"existingSecret\":\"db\"}";

    @Test
    void defaultsFollowTheHelmChart() throws Exception {
        InstallationSpec s = InstallationSpec.parse(cr("sangam", "{\"image\":\"r/s:1\"," + DB + "}"));
        assertEquals(2, s.replicas());
        assertEquals("job", s.migration().mode());
        assertEquals(0, s.migration().backoffLimit());
        assertEquals(1800, s.migration().activeDeadlineSeconds());
        assertEquals("oracle,oidc", s.springProfilesActive());
        assertTrue(s.javaToolOptions().contains("-Duser.timezone=UTC"));
        assertEquals("50Gi", s.state().size());
        assertEquals("ReadWriteMany", s.state().accessModes().get(0).asText());
        assertEquals("2Gi", s.passthrough().resources().path("requests").path("memory").asText());
        assertEquals(3, s.generation());
    }

    @Test
    void refusesWhatTheServerOrKubernetesWouldRejectLater() {
        assertThrows(InstallationSpec.InvalidSpec.class, () -> InstallationSpec.parse(cr("a".repeat(41), "{\"image\":\"r/s:1\"," + DB + "}")));
        assertThrows(InstallationSpec.InvalidSpec.class, () -> InstallationSpec.parse(cr("Bad_Name", "{\"image\":\"r/s:1\"," + DB + "}")));
        assertThrows(InstallationSpec.InvalidSpec.class, () -> InstallationSpec.parse(cr("s", "{" + DB + "}")));
        assertThrows(InstallationSpec.InvalidSpec.class, () -> InstallationSpec.parse(cr("s",
                "{\"image\":\"r/s:1\",\"database\":{\"type\":\"db2\",\"jdbcUrl\":\"jdbc:db2://x\",\"existingSecret\":\"d\"}}")));
        assertThrows(InstallationSpec.InvalidSpec.class, () -> InstallationSpec.parse(cr("s",
                "{\"image\":\"r/s:1\",\"database\":{\"type\":\"postgres\",\"jdbcUrl\":\"postgres://x\",\"existingSecret\":\"d\"}}")));
        assertThrows(InstallationSpec.InvalidSpec.class, () -> InstallationSpec.parse(cr("s",
                "{\"image\":\"r/s:1\"," + DB + ",\"migration\":{\"mode\":\"startup\"}}")),
                "startup migrations from every pod are a chart option for dev installs, not an operator mode");
        assertThrows(InstallationSpec.InvalidSpec.class, () -> InstallationSpec.parse(cr("s",
                "{\"image\":\"r/s:1\"," + DB + ",\"env\":[{\"name\":\"JAVA_TOOL_OPTIONS\",\"value\":\"x\"}]}")));
    }

    @Test
    void migrationJobNamesAreDeterministicPerImageAndStayDnsLabels() throws Exception {
        InstallationSpec s = InstallationSpec.parse(cr("a".repeat(40), "{\"image\":\"r/s:1\"," + DB + "}"));
        String one = Manifests.migrationJobName(s, "r/s:1");
        assertEquals(one, Manifests.migrationJobName(s, "r/s:1"));
        assertNotEquals(one, Manifests.migrationJobName(s, "r/s:2"));
        assertTrue(one.length() <= 63);
    }

    @Test
    void healthNeedsARecentCompletedPass() {
        Instant[] now = {Instant.parse("2026-11-12T17:00:00Z")};
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            public Instant instant() {
                return now[0];
            }
        };
        FakeKube kube = new FakeKube();
        OperatorMain op = new OperatorMain(new Reconciler(kube, System.err), "n", Duration.ofSeconds(15), clock);
        assertFalse(op.healthy(), "no pass yet");
        assertTrue(op.pass());
        assertTrue(op.healthy());
        now[0] = now[0].plusSeconds(61);
        assertFalse(op.healthy(), "stale after four resync periods");
    }
}
