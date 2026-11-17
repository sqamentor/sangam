// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.pnp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.SangamEngineSettingsBinder;

/** R8-pnp PNP-1: sangam.engine.* from plain properties, fail closed. */
class SettingsBinderTest {

    @Test
    void binderBindsTheAllowListAndRefusesBadValues() {
        Properties p = new Properties();
        p.setProperty("sangam.engine.deploy-allowed-beans.*", "recorder.record, other.member");
        p.setProperty("sangam.engine.deploy-allowed-classes.t1", "com.dept.Delegate");
        p.setProperty("sangam.engine.allow-multiple-engines-per-jvm", "true");
        p.setProperty("sangam.engine.lock-timeout", "PT3S");
        p.setProperty("unrelated.key", "ignored");
        SangamEngineSettings s = SangamEngineSettingsBinder.bind(new SangamEngineSettings(), p);
        assertEquals(Set.of("recorder.record", "other.member"), s.getDeployAllowedBeans().get("*"));
        assertEquals(Set.of("com.dept.Delegate"), s.getDeployAllowedClasses().get("t1"));
        assertTrue(s.isAllowMultipleEnginesPerJvm());
        assertEquals(java.time.Duration.ofSeconds(3), s.getLockTimeout());

        Properties bad = new Properties();
        bad.setProperty("sangam.engine.allow-multiple-engines-per-jvm", "yes");
        assertThrows(IllegalArgumentException.class, () -> SangamEngineSettingsBinder.bind(new SangamEngineSettings(), bad));
        Properties port = new Properties();
        port.setProperty("sangam.engine.audit-sink", "x"); // a port is never bound from text
        assertThrows(IllegalArgumentException.class, () -> SangamEngineSettingsBinder.bind(new SangamEngineSettings(), port));
        assertFalse(SangamEngineSettingsBinder.keys().contains("sangam.engine.audit-sink"));
        assertTrue(SangamEngineSettingsBinder.keys().contains("sangam.engine.deploy-allowed-beans"));
    }
}
