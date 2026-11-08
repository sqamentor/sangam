// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.feel;

import java.io.InputStream;
import java.util.Properties;

import org.operaton.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration;

/**
 * R10: the identity of the FEEL engine a model version is bound to: the DMN engine's FEEL engine factory, the
 * {@code org.camunda.feel:feel-engine} version on the class path and the legacy-behaviour flag, for example
 * {@code ScalaFeelEngineFactory/feel-engine-1.22.1/strict}. Read from Maven metadata, no reflection.
 */
public final class FeelEngineIdentity {

    private FeelEngineIdentity() {
    }

    public static String of(DefaultDmnEngineConfiguration dmn) {
        String factory = dmn == null || dmn.getFeelEngineFactory() == null ? "default"
                : dmn.getFeelEngineFactory().getClass().getSimpleName();
        boolean legacy = dmn != null && dmn.isEnableFeelLegacyBehavior();
        return factory + "/feel-engine-" + feelEngineVersion() + "/" + (legacy ? "legacy" : "strict");
    }

    static String feelEngineVersion() {
        try (InputStream in = FeelEngineIdentity.class.getClassLoader()
                .getResourceAsStream("META-INF/maven/org.camunda.feel/feel-engine/pom.properties")) {
            if (in == null) {
                return "unknown";
            }
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("version", "unknown");
        } catch (java.io.IOException e) {
            return "unknown";
        }
    }
}
