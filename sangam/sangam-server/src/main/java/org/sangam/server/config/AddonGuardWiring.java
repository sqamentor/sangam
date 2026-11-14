// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Owner decisions 232 and 235: the optional add-ons that carry Bouncy Castle. The server jar ships neither
 * {@code io.nats:jnats} nor Bouncy Castle; the NATS add-on (jnats with bcprov-lts8on) and the eSign add-on
 * (sangam-esign with the bcprov/bcpkix/bcutil jdk18on jars) are copied into {@code /opt/sangam/lib-ext} by the image
 * build. In 0.1.0 a server runs one of them, never both: the two Bouncy Castle providers carry the same packages, so
 * which one a class comes from would depend on class path order. This configuration is created at start-up and refuses
 * to start when
 * <ul>
 *   <li>the NATS add-on ({@value #NATS_CLASS}) and the eSign add-on ({@value #ESIGN_CLASS}) are both present, or</li>
 *   <li>more than one jar provides {@value #BC_PROVIDER_RESOURCE}, or</li>
 *   <li>owner decision 243: the health module is on ({@value #HEALTH_MODULE_PROPERTY}=true) while the NATS add-on is
 *       present; the health module's Fidelius uses bcprov-jdk18on and never runs beside the NATS add-on's
 *       bcprov-lts8on, so this refusal holds even before a second provider jar is on the class path.</li>
 * </ul>
 * Presence is read as a class resource, so nothing is loaded or linked by the check.
 */
@Configuration(proxyBeanMethods = false)
public class AddonGuardWiring {

    /** The class the NATS add-on provides; NATS settings and NATS channels key on it (W4-C REQUEST 02:25:52Z). */
    public static final String NATS_CLASS = "io.nats.client.Nats";
    /** The eSign add-on's ServiceLoader implementation of DSC signing (sangam-esign). */
    public static final String ESIGN_CLASS = "org.sangam.esign.DscSigningProvider";
    /** One class every Bouncy Castle provider jar (bcprov-jdk18on, bcprov-lts8on) contains. */
    public static final String BC_PROVIDER_RESOURCE = "org/bouncycastle/jce/provider/BouncyCastleProvider.class";
    /** How start-up messages and 501 problems name the NATS add-on (W4-C distribution {@code nats}). */
    public static final String NATS_ADDON = "the NATS add-on (sangam-nats: io.nats:jnats with bcprov-lts8on in "
            + "/opt/sangam/lib-ext, image build argument WITH_NATS=true; owner decision 232)";

    /** The health module's switch (HealthConnectorsWiring: on only when exactly true). */
    public static final String HEALTH_MODULE_PROPERTY = "sangam.modules.connectors-health.enabled";

    public AddonGuardWiring(Environment env) {
        check(AddonGuardWiring.class.getClassLoader(), healthModuleOn(env));
    }

    /** As HealthConnectorsWiring reads it: on only when the property is true (missing means off). */
    static boolean healthModuleOn(Environment env) {
        return "true".equalsIgnoreCase(String.valueOf(env.getProperty(HEALTH_MODULE_PROPERTY, "false")).trim());
    }

    /** True when the NATS add-on is on the class path of {@code loader}. */
    public static boolean natsPresent(ClassLoader loader) {
        return present(loader, NATS_CLASS);
    }

    /** True when the eSign add-on is on the class path of {@code loader}. */
    public static boolean esignPresent(ClassLoader loader) {
        return present(loader, ESIGN_CLASS);
    }

    /** Refuses start-up for the decision 235 combinations; see the class comment. */
    static void check(ClassLoader loader) {
        check(loader, false);
    }

    /** Refuses start-up for the decision 235 and 243 combinations; see the class comment. */
    static void check(ClassLoader loader, boolean healthModuleOn) {
        if (healthModuleOn && natsPresent(loader)) {
            throw new IllegalStateException("owner decision 243: the health module (" + HEALTH_MODULE_PROPERTY
                    + "=true) never runs with the NATS add-on (" + NATS_CLASS + ") on the class path; switch the health"
                    + " module off or remove the NATS add-on from /opt/sangam/lib-ext (image build: WITH_NATS)");
        }
        if (natsPresent(loader) && esignPresent(loader)) {
            throw new IllegalStateException("owner decision 235: the NATS add-on (" + NATS_CLASS + ") and the eSign "
                    + "add-on (" + ESIGN_CLASS + ") are both on the class path; a 0.1.0 server runs one of them, never "
                    + "both. Remove one from /opt/sangam/lib-ext (image build: WITH_NATS or WITH_ESIGN, not both)");
        }
        List<URL> providers = resources(loader, BC_PROVIDER_RESOURCE);
        if (providers.size() > 1) {
            throw new IllegalStateException("owner decision 235: more than one Bouncy Castle provider jar is on the "
                    + "class path (" + providers.size() + ": " + providers + "); keep exactly one add-on in "
                    + "/opt/sangam/lib-ext");
        }
    }

    private static boolean present(ClassLoader loader, String className) {
        return loader.getResource(className.replace('.', '/') + ".class") != null;
    }

    private static List<URL> resources(ClassLoader loader, String name) {
        try {
            return new ArrayList<>(Collections.list(loader.getResources(name)));
        } catch (IOException e) {
            throw new IllegalStateException("owner decision 235: cannot list " + name + " on the class path", e);
        }
    }
}
