// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.sangam.compliance.ports.CompliancePorts;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.RetryPolicy;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.connector.gov.DigiLockerRequesterConnector;
import org.sangam.integration.packs.PackConnectorBindings;
import org.sangam.server.bridge.ConfigConnectorBinder;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * M-072: the DigiLocker requester (pull with consent), opt-in with
 * {@code sangam.connectors.digilocker.requester.enabled=true}.
 *
 * <pre>
 * sangam.connectors.digilocker.requester.base-url=https://...      (https on a production profile)
 * .client-id  .client-secret-ref=secret://digilocker/client-secret  .redirect-uri=https://.../callback
 * .age-of-majority=18  .zone=Asia/Kolkata  .timeout=30s  .egress=<extra allow-list entries, default the base origin>
 * .method-codes=DIGILOCKER_TOKEN   (the jurisdiction pack's verification.child codes this verifier answers)
 * .tenants=t1,t2                    (tenants that get the connector "digilocker-requester" in their registry)
 * .paths.authorize / .token / .user / .issued / .file   (default: the DigiLocker requester paths)
 * </pre>
 * Publishes {@link CompliancePorts.AdultIdentityVerifier} (bound by ComplianceWiring through an ObjectProvider, W3-E
 * CHANGE 11:28:33Z) and registers the connector for the listed tenants. A method code it does not handle answers
 * {@code identifiable=false, detail=METHOD_NOT_SUPPORTED}; a refused consent {@code CONSENT_INVALID}; an unreachable
 * DigiLocker {@code UNAVAILABLE} (never a guessed "adult").
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.connectors.digilocker.requester.enabled", havingValue = "true")
public class DigiLockerWiring {

    static final String P = "sangam.connectors.digilocker.requester.";

    @Bean
    public DigiLockerRequesterConnector sangamDigiLockerRequester(Environment env, ObjectProvider<SecretResolver> secrets) {
        boolean production = ProductionEnvironment.isProduction(env);
        String base = required(env, "base-url");
        URI baseUri = URI.create(base.trim());
        if (production && !"https".equalsIgnoreCase(baseUri.getScheme())) {
            throw new IllegalStateException(P + "base-url must be https on a production profile");
        }
        String secretRef = required(env, "client-secret-ref");
        if (production && !secretRef.startsWith("secret://")) {
            throw new IllegalStateException(P + "client-secret-ref must be a secret:// reference on a production profile");
        }
        List<EgressPolicy.Rule> rules = new ArrayList<>();
        String[] egress = env.getProperty(P + "egress", String[].class, new String[0]);
        if (egress.length == 0) {
            rules.add(EgressPolicy.Rule.parse("*", baseUri.getScheme() + "://" + baseUri.getHost()
                    + (baseUri.getPort() == -1 ? "" : ":" + baseUri.getPort()), false));
        } else {
            for (String e : egress) {
                rules.add(EgressPolicy.Rule.parse("*", e.trim(), false));
            }
        }
        Duration timeout = env.getProperty(P + "timeout", Duration.class, Duration.ofSeconds(30));
        HttpTransport transport = new HttpTransport(EgressPolicy.of(rules, 0, 16L * 1024 * 1024), null,
                Duration.ofSeconds(10), timeout, RetryPolicy.none());
        return new DigiLockerRequesterConnector(new DigiLockerRequesterConnector.Config("digilocker-requester", baseUri,
                paths(env), required(env, "client-id"), secretRef,
                URI.create(required(env, "redirect-uri")), secrets.getIfAvailable(SecretResolver::fromEnvironment),
                transport, ConnectorProfile.LIVE, timeout, env.getProperty(P + "age-of-majority", Integer.class, 18),
                ZoneId.of(env.getProperty(P + "zone", "Asia/Kolkata")), 0, Clock.systemUTC()));
    }

    @Bean
    public CompliancePorts.AdultIdentityVerifier sangamDigiLockerAdultVerifier(DigiLockerRequesterConnector requester,
                                                                                Environment env) {
        Set<String> codes = Arrays.stream(env.getProperty(P + "method-codes", "DIGILOCKER_TOKEN").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        return verifier(requester, codes);
    }

    static CompliancePorts.AdultIdentityVerifier verifier(DigiLockerRequesterConnector requester, Set<String> codes) {
        return (tenantId, personRef, methodCode, evidence) -> {
            if (methodCode == null || !codes.contains(methodCode.toUpperCase(Locale.ROOT))) {
                return new CompliancePorts.AdultVerification(false, false, "DigiLocker", null, "METHOD_NOT_SUPPORTED");
            }
            try {
                DigiLockerRequesterConnector.AdultCheck a = requester.verifyAdult(tenantId,
                        evidence == null ? Map.of() : evidence);
                return new CompliancePorts.AdultVerification(a.identifiable(), a.adult(), a.issuer(), a.tokenRef(),
                        a.detail());
            } catch (DigiLockerRequesterConnector.ConsentException e) {
                return new CompliancePorts.AdultVerification(false, false, "DigiLocker", null, "CONSENT_INVALID");
            } catch (DigiLockerRequesterConnector.UnavailableException e) {
                return new CompliancePorts.AdultVerification(false, false, "DigiLocker", null, "UNAVAILABLE");
            }
        };
    }

    @Bean
    public InitializingBean sangamDigiLockerRequesterRegistration(DigiLockerRequesterConnector requester, Environment env,
            ObjectProvider<ConnectorRegistry> registry, ObjectProvider<PackConnectorBindings> bindings) {
        return () -> {
            ConfigConnectorBinder binder = new ConfigConnectorBinder(registry, bindings);
            for (String t : env.getProperty(P + "tenants", String[].class, new String[0])) {
                if (!t.isBlank() && !binder.register(t.trim(), requester)) {
                    throw new IllegalStateException(P + "tenants is set but the connector registry is not available");
                }
            }
        };
    }

    /** {@code .paths.{authorize,token,user,issued,file}}: each defaults to {@link DigiLockerRequesterConnector.Paths#DEFAULT}
     *  (for a gateway such as API Setu that publishes the same API under other paths). */
    static DigiLockerRequesterConnector.Paths paths(Environment env) {
        DigiLockerRequesterConnector.Paths d = DigiLockerRequesterConnector.Paths.DEFAULT;
        return new DigiLockerRequesterConnector.Paths(env.getProperty(P + "paths.authorize", d.authorize()),
                env.getProperty(P + "paths.token", d.token()), env.getProperty(P + "paths.user", d.user()),
                env.getProperty(P + "paths.issued", d.issued()), env.getProperty(P + "paths.file", d.file()));
    }

    private static String required(Environment env, String key) {
        String v = env.getProperty(P + key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(P + key + " is required");
        }
        return v;
    }
}
