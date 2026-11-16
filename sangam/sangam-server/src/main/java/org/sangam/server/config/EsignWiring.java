// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.sangam.core.integration.ConnectorProfile;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.RetryPolicy;
import org.sangam.integration.connector.TlsConfig;
import org.sangam.integration.connector.gov.EsignConnector;
import org.sangam.integration.connector.gov.EsignRequestSigner;
import org.sangam.integration.signature.EsignResponseVerifier;
import org.sangam.integration.signature.SignaturePolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * DEC-140 server wiring (owner decisions 140, 215 and 217): the live eSign connector, opt-in with
 * {@code sangam.integration.esign.enabled=true} (default off; decision 215 keeps live eSign blocked until one real ESP
 * sandbox response confirms the profile). Enabled, start-up fails ({@value #CONFIG_INVALID} or {@value #MODULE_MISSING})
 * unless every required setting is present and the optional module sangam-esign supplies the decision 140 verifier, so
 * the server never holds an eSign connector that signs without verifying. {@link IntegrationWiring} registers the
 * connector in the connector registry.
 *
 * <pre>
 * sangam.integration.esign.enabled=true
 * sangam.integration.esign.id=esign                        (connector id; default esign)
 * sangam.integration.esign.base-uri=https://esp.example/esign   (https only; also the only egress origin)
 * sangam.integration.esign.asp-id=ASP-ID
 * sangam.integration.esign.response-url=https://asp.example/esign/response   (optional)
 * sangam.integration.esign.response-sig-type=PKCS7pdf      (PKCS7, PKCS7pdf or PKCS7complete; default PKCS7pdf)
 * sangam.integration.esign.timeout=60s
 * sangam.integration.esign.policy-version=esign-2026-11     (recorded with every verification)
 * sangam.integration.esign.trust-anchors=/etc/sangam/esign/cca-root.pem[,...]        (the only trusted roots)
 * sangam.integration.esign.intermediates=/etc/sangam/esign/ca.pem[,...]               (optional, not trusted)
 * sangam.integration.esign.response-signer-certificates=/etc/sangam/esign/esp.pem     (the ESP's XML signing certificate)
 * sangam.integration.esign.response-time-zone=Asia/Kolkata  (optional: for a response ts without an offset)
 * sangam.integration.esign.revocation-egress=http://ocsp.ca.example,http://crl.ca.example   (OCSP and CRL origins)
 * sangam.integration.esign.revocation-timeout=10s  min-rsa-bits=2048  max-response-age=30m  ocsp-max-age=24h
 * sangam.integration.esign.request-signature.key-store=/run/secrets/asp.p12           (PKCS#12, the ASP key)
 * sangam.integration.esign.request-signature.key-store-password-file=/run/secrets/asp.p12.pass   (first line)
 * sangam.integration.esign.request-signature.key-alias=asp  (optional when the key store holds one key)
 * sangam.integration.esign.request-signature.canonicalization=c14n | exc-c14n | algorithm URI
 * sangam.integration.esign.request-signature.signature-method=rsa-sha256 | rsa-sha384 | rsa-sha512 | URI
 * sangam.integration.esign.request-signature.digest-method=sha256 | sha384 | sha512 | URI
 * sangam.integration.esign.request-signature.include-certificate=true | false
 * </pre>
 * The request-signature algorithms and the certificate in KeyInfo have no default: they are the ESP's profile, which
 * the operator states after the sandbox confirmation (decision 215), not a guess of this code.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.integration.esign.enabled", havingValue = "true")
public class EsignWiring {

    static final String P = "sangam.integration.esign.";
    static final String RS = P + "request-signature.";
    /** Start-up refusal: a setting is missing or unusable. */
    static final String CONFIG_INVALID = "ESIGN_CONFIG_INVALID";
    /** Start-up refusal: the decision 140 verifier (optional module sangam-esign) is not installed. */
    static final String MODULE_MISSING = "ESIGN_MODULE_MISSING";

    /** Loads the decision 140 verifier; {@link EsignResponseVerifier#load} in the server, a stub in tests. */
    @FunctionalInterface
    interface VerifierLoader {
        Optional<EsignResponseVerifier> load(SignaturePolicy policy, HttpTransport revocation, Clock clock);
    }

    @Bean
    public EsignConnector sangamLiveEsignConnector(Environment env) {
        return connector(env, EsignResponseVerifier::load);
    }

    static EsignConnector connector(Environment env, VerifierLoader loader) {
        SignaturePolicy policy = policy(env);
        HttpTransport revocation = DscSigningWiring.revocationTransport(env, P, policy.revocationTimeout());
        EsignResponseVerifier verifier = loader.load(policy, revocation, Clock.systemUTC()).orElseThrow(
                () -> new IllegalStateException(MODULE_MISSING + ": " + P + "enabled is true but no eSign response"
                        + " verifier is installed; add the optional module sangam-esign (deploy README, eSign and DSC"
                        + " add-on)"));
        URI base = uri(env, "base-uri", true);
        if (!"https".equalsIgnoreCase(base.getScheme())) {
            throw invalid("base-uri", "must be an https URI");
        }
        HttpTransport transport = new HttpTransport(EgressPolicy.of(List.of(EgressPolicy.Rule.parse("*",
                base.getScheme() + "://" + base.getRawAuthority(), false)), 0, 8L * 1024 * 1024),
                TlsConfig.systemDefault(), Duration.ofSeconds(10), null, RetryPolicy.none());
        try {
            return new EsignConnector(new EsignConnector.Config(env.getProperty(P + "id", "esign").trim(), base,
                    required(env, "asp-id"), uri(env, "response-url", false), requestSigner(env), transport,
                    ConnectorProfile.LIVE, null, env.getProperty(P + "timeout", Duration.class), verifier,
                    env.getProperty(P + "response-sig-type")));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(CONFIG_INVALID + ": " + e.getMessage(), e);
        }
    }

    static SignaturePolicy policy(Environment env) {
        String version = required(env, "policy-version");
        Set<TrustAnchor> anchors = new LinkedHashSet<>();
        for (X509Certificate c : certificates(env, "trust-anchors")) {
            anchors.add(new TrustAnchor(c, null));
        }
        if (anchors.isEmpty()) {
            throw invalid("trust-anchors", "is required: no eSign signature can verify without a trusted root");
        }
        List<X509Certificate> esp = certificates(env, "response-signer-certificates");
        if (esp.isEmpty()) {
            throw invalid("response-signer-certificates", "is required: the ESP's XML response signing certificate");
        }
        String zone = env.getProperty(P + "response-time-zone");
        ZoneId tz;
        try {
            tz = zone == null || zone.isBlank() ? null : ZoneId.of(zone.trim());
        } catch (RuntimeException e) {
            throw invalid("response-time-zone", "is not a time zone id");
        }
        return new SignaturePolicy(version, anchors, certificates(env, "intermediates"), esp,
                env.getProperty(P + "max-response-age", Duration.class), null,
                env.getProperty(P + "min-rsa-bits", Integer.class, 2048),
                env.getProperty(P + "revocation-timeout", Duration.class, Duration.ofSeconds(10)), tz,
                env.getProperty(P + "ocsp-max-age", Duration.class, SignaturePolicy.DEFAULT_OCSP_MAX_AGE));
    }

    static EsignRequestSigner requestSigner(Environment env) {
        String store = env.getProperty(RS + "key-store");
        String passwordFile = env.getProperty(RS + "key-store-password-file");
        if (store == null || store.isBlank()) {
            throw invalid("request-signature.key-store", "is required (PKCS#12 holding the ASP signing key)");
        }
        if (passwordFile == null || passwordFile.isBlank()) {
            throw invalid("request-signature.key-store-password-file", "is required");
        }
        String include = env.getProperty(RS + "include-certificate");
        if (include == null || !(include.trim().equals("true") || include.trim().equals("false"))) {
            throw invalid("request-signature.include-certificate", "is required (true or false, the ESP's profile)");
        }
        EsignRequestSigner.Profile profile;
        try {
            profile = new EsignRequestSigner.Profile(
                    EsignRequestSigner.algorithm(env.getProperty(RS + "canonicalization")),
                    EsignRequestSigner.algorithm(env.getProperty(RS + "signature-method")),
                    EsignRequestSigner.algorithm(env.getProperty(RS + "digest-method")),
                    Boolean.parseBoolean(include.trim()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(CONFIG_INVALID + ": " + RS + "*: " + e.getMessage(), e);
        }
        char[] password;
        try {
            List<String> lines = Files.readAllLines(Path.of(passwordFile.trim()), StandardCharsets.UTF_8);
            password = (lines.isEmpty() ? "" : lines.get(0)).toCharArray();
        } catch (Exception e) {
            throw invalid("request-signature.key-store-password-file", "cannot be read");
        }
        try (InputStream in = Files.newInputStream(Path.of(store.trim()))) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, password);
            String alias = env.getProperty(RS + "key-alias");
            if (alias == null || alias.isBlank()) {
                List<String> keys = new ArrayList<>();
                for (String a : Collections.list(ks.aliases())) {
                    if (ks.isKeyEntry(a)) {
                        keys.add(a);
                    }
                }
                if (keys.size() != 1) {
                    throw invalid("request-signature.key-alias", "is required: the key store holds " + keys.size()
                            + " keys");
                }
                alias = keys.get(0);
            }
            if (!(ks.getKey(alias.trim(), password) instanceof PrivateKey key)
                    || !(ks.getCertificate(alias.trim()) instanceof X509Certificate cert)) {
                throw invalid("request-signature.key-alias", "names no private key with an X.509 certificate");
            }
            return new EsignRequestSigner(key, cert, profile);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("request-signature.key-store", "cannot be read as PKCS#12 with the given password ("
                    + e.getClass().getSimpleName() + ")");
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static List<X509Certificate> certificates(Environment env, String key) {
        try {
            return DscSigningWiring.certificates(env.getProperty(P + key, String[].class, new String[0]));
        } catch (IllegalStateException e) {
            throw invalid(key, "cannot be read: " + e.getMessage());
        }
    }

    private static String required(Environment env, String key) {
        String v = env.getProperty(P + key);
        if (v == null || v.isBlank()) {
            throw invalid(key, "is required");
        }
        return v.trim();
    }

    private static URI uri(Environment env, String key, boolean required) {
        String v = required ? required(env, key) : env.getProperty(P + key);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            URI u = URI.create(v.trim());
            if (u.getScheme() == null || u.getHost() == null) {
                throw new IllegalArgumentException("no scheme or host");
            }
            return u;
        } catch (IllegalArgumentException e) {
            throw invalid(key, "is not an absolute URI");
        }
    }

    private static IllegalStateException invalid(String key, String why) {
        return new IllegalStateException(CONFIG_INVALID + ": " + P + key + " " + why);
    }
}
