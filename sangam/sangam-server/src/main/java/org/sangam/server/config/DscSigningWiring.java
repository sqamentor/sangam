// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.RetryPolicy;
import org.sangam.integration.dsc.DscEnrolment;
import org.sangam.integration.dsc.DscSigningFlow;
import org.sangam.integration.dsc.JdbcDscCertificateEnrolments;
import org.sangam.integration.dsc.JdbcDscPreparedStore;
import org.sangam.integration.signature.DscSignatureVerifier;
import org.sangam.integration.signature.DscSigning;
import org.sangam.integration.signature.SignaturePolicy;
import org.sangam.rest.support.TxSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Owner decisions 165, 140 and 217: DSC signing for {@code DscSigningController}, opt-in with
 * {@code sangam.dsc.enabled=true}. Without it (the default) there is no {@link DscSigningFlow} and both endpoints
 * answer 501. When it is enabled, startup fails unless the optional module sangam-esign is on the class path and the
 * policy is complete, so a half-configured server never prepares or accepts a DSC signature.
 *
 * <pre>
 * sangam.dsc.enabled=true
 * sangam.dsc.policy-version=dsc-2026-11            (recorded with every outcome)
 * sangam.dsc.trust-anchors=/etc/sangam/dsc/root.pem[,...]     (PEM or DER files; the only trusted roots)
 * sangam.dsc.intermediates=/etc/sangam/dsc/ca.pem[,...]       (optional; offered for path building, not trusted)
 * sangam.dsc.revocation-egress=http://ocsp.ca.example,http://crl.ca.example   (OCSP and CRL origins allowed)
 * sangam.dsc.revocation-timeout=10s  sangam.dsc.prepare-ttl=10m  sangam.dsc.min-rsa-bits=2048
 * sangam.dsc.ocsp-max-age=24h      (decision 259: an older OCSP thisUpdate is not used, even with a matching nonce)
 * </pre>
 * Prepared state lives in table {@code INTEGRATION_DSC_PREPARED}, enrolled certificates (owner decision 227,
 * {@code DscCertificateController}) in {@code INTEGRATION_DSC_CERTIFICATE} (sangam-persistence changelogs).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.dsc.enabled", havingValue = "true")
public class DscSigningWiring {

    static final String P = "sangam.dsc.";

    /**
     * Owner decision 227: enrolled certificates. The store joins the caller's transaction ({@link TxSupport}), so a
     * decision and its audit record commit together.
     */
    @Bean
    public DscEnrolment sangamDscEnrolment(ObjectProvider<TxSupport> txSupport) {
        TxSupport tx = txSupport.getIfAvailable();
        if (tx == null) {
            throw new IllegalStateException(P + "enabled is true but there is no TxSupport for the enrolments");
        }
        return new DscEnrolment(new JdbcDscCertificateEnrolments(tx::withConnection), Clock.systemUTC());
    }

    @Bean
    public DscSigningFlow sangamDscSigningFlow(Environment env, ObjectProvider<DataSource> dataSource,
                                               DscEnrolment enrolment) {
        DscSigning signing = DscSigning.load().orElseThrow(() -> new IllegalStateException(P + "enabled is true but the"
                + " optional module sangam-esign is not on the class path"));
        SignaturePolicy policy = policy(env);
        HttpTransport revocation = revocationTransport(env, policy.revocationTimeout());
        Clock clock = Clock.systemUTC();
        DscSignatureVerifier verifier = DscSignatureVerifier.load(policy, revocation, clock).orElseThrow(
                () -> new IllegalStateException(P + "enabled is true but no DSC verifier is installed (sangam-esign)"));
        DataSource ds = dataSource.getIfAvailable();
        if (ds == null) {
            throw new IllegalStateException(P + "enabled is true but there is no DataSource for the prepared state");
        }
        return new DscSigningFlow(signing, verifier, new JdbcDscPreparedStore(Transactions.of(ds)), enrolment, clock,
                env.getProperty(P + "prepare-ttl", Duration.class, DscSigningFlow.DEFAULT_TTL), policy.version());
    }

    static SignaturePolicy policy(Environment env) {
        String version = env.getProperty(P + "policy-version");
        if (version == null || version.isBlank()) {
            throw new IllegalStateException(P + "policy-version is required");
        }
        Set<TrustAnchor> anchors = new LinkedHashSet<>();
        for (X509Certificate c : certificates(env.getProperty(P + "trust-anchors", String[].class, new String[0]))) {
            anchors.add(new TrustAnchor(c, null));
        }
        if (anchors.isEmpty()) {
            throw new IllegalStateException(P + "trust-anchors is required: no DSC can verify without a trusted root");
        }
        List<X509Certificate> intermediates = certificates(env.getProperty(P + "intermediates", String[].class,
                new String[0]));
        return new SignaturePolicy(version.trim(), anchors, intermediates, List.of(), null, null,
                env.getProperty(P + "min-rsa-bits", Integer.class, 2048),
                env.getProperty(P + "revocation-timeout", Duration.class, Duration.ofSeconds(10)), null,
                env.getProperty(P + "ocsp-max-age", Duration.class, SignaturePolicy.DEFAULT_OCSP_MAX_AGE));
    }

    static HttpTransport revocationTransport(Environment env, Duration timeout) {
        return revocationTransport(env, P, timeout);
    }

    /** The revocation transport of the signing feature under {@code prefix} (DSC, or eSign in {@link EsignWiring}). */
    static HttpTransport revocationTransport(Environment env, String prefix, Duration timeout) {
        List<EgressPolicy.Rule> rules = new ArrayList<>();
        for (String origin : env.getProperty(prefix + "revocation-egress", String[].class, new String[0])) {
            if (!origin.isBlank()) {
                rules.add(EgressPolicy.Rule.parse("*", origin.trim(), false));
            }
        }
        // no rule = no OCSP or CRL can be fetched = every verification is INDETERMINATE (revocation is never skipped)
        return new HttpTransport(EgressPolicy.of(rules, 0, 8L * 1024 * 1024), null, Duration.ofSeconds(5), timeout,
                RetryPolicy.none());
    }

    static List<X509Certificate> certificates(String[] files) {
        List<X509Certificate> out = new ArrayList<>();
        try {
            CertificateFactory f = CertificateFactory.getInstance("X.509");
            for (String file : files) {
                if (file == null || file.isBlank()) {
                    continue;
                }
                try (InputStream in = Files.newInputStream(Path.of(file.trim()))) {
                    for (var c : f.generateCertificates(in)) {
                        out.add((X509Certificate) c);
                    }
                }
            }
        } catch (IOException | CertificateException e) {
            throw new IllegalStateException(P + " certificate file cannot be read: " + e.getMessage(), e);
        }
        return List.copyOf(out);
    }
}
