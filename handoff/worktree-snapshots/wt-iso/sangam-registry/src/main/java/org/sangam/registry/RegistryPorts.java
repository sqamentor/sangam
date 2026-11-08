// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry;

import org.sangam.core.layer.SqlPredicate;
import org.sangam.registry.support.Ed25519Signer;
import org.sangam.registry.support.HmacIdentifierVault;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The ports of the registry. Other modules implement them and the integrator wires them in sangam-server; the
 * registry never depends on those modules directly (ARCHITECTURE.md section 3). Every port has a safe default so the
 * module also runs standalone (a small office, tests).
 *
 * @param events     registry events to the outbox, in the caller's transaction (VI.2)
 * @param signer     signatures for QR payloads and certified extracts (eSign / HSM / key store)
 * @param documents  document generation from an output template (content service, C11)
 * @param publisher  push of issued documents to an external wallet or locker (document locker / wallet connector)
 * @param consent    consent and purpose checks, owned by the compliance module (R53)
 * @param privacy    purpose-based masking of party and instrument views (R19 masks, DPDP-style minimisation)
 * @param vault      protection of sensitive identifiers (vault tokens and keyed hashes, R50)
 * @param matcher    optional probabilistic matcher worker (plugged in next to the built-in matcher)
 * @param visibility row visibility predicate from the security module's AuthorizedQueryBuilder (VI.4)
 * @param decisions  DMN evaluation (deemed renewal, fee and eligibility rules)
 * @param records    external system of record for facade mode (mirror instead of master)
 * @param metrics    lock-wait and throughput metrics (Micrometer bridge)
 */
public record RegistryPorts(EventPort events, SignerPort signer, DocumentPort documents, PublishPort publisher,
                            ConsentPort consent, PrivacyPort privacy, IdentifierVault vault, ExternalMatcher matcher,
                            VisibilityPort visibility, DecisionPort decisions, SystemOfRecordPort records,
                            MetricsPort metrics) {

    public RegistryPorts {
        events = events == null ? EventPort.NONE : events;
        signer = signer == null ? Ed25519Signer.ephemeral() : signer;
        documents = documents == null ? DocumentPort.NONE : documents;
        publisher = publisher == null ? PublishPort.NONE : publisher;
        consent = consent == null ? ConsentPort.PERMIT_ALL : consent;
        privacy = privacy == null ? PrivacyPort.NONE : privacy;
        vault = vault == null ? HmacIdentifierVault.ephemeral() : vault;
        matcher = matcher == null ? ExternalMatcher.NONE : matcher;
        visibility = visibility == null ? VisibilityPort.TENANT_ONLY : visibility;
        decisions = decisions == null ? DecisionPort.NONE : decisions;
        records = records == null ? SystemOfRecordPort.NONE : records;
        metrics = metrics == null ? MetricsPort.NONE : metrics;
    }

    /** All defaults (standalone). */
    public static RegistryPorts defaults() {
        return new RegistryPorts(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public RegistryPorts withEvents(EventPort p) {
        return new RegistryPorts(p, signer, documents, publisher, consent, privacy, vault, matcher, visibility, decisions, records, metrics);
    }

    public RegistryPorts withSigner(SignerPort p) {
        return new RegistryPorts(events, p, documents, publisher, consent, privacy, vault, matcher, visibility, decisions, records, metrics);
    }

    public RegistryPorts withDocuments(DocumentPort p) {
        return new RegistryPorts(events, signer, p, publisher, consent, privacy, vault, matcher, visibility, decisions, records, metrics);
    }

    public RegistryPorts withPublisher(PublishPort p) {
        return new RegistryPorts(events, signer, documents, p, consent, privacy, vault, matcher, visibility, decisions, records, metrics);
    }

    public RegistryPorts withConsent(ConsentPort p) {
        return new RegistryPorts(events, signer, documents, publisher, p, privacy, vault, matcher, visibility, decisions, records, metrics);
    }

    public RegistryPorts withPrivacy(PrivacyPort p) {
        return new RegistryPorts(events, signer, documents, publisher, consent, p, vault, matcher, visibility, decisions, records, metrics);
    }

    public RegistryPorts withVault(IdentifierVault p) {
        return new RegistryPorts(events, signer, documents, publisher, consent, privacy, p, matcher, visibility, decisions, records, metrics);
    }

    public RegistryPorts withMatcher(ExternalMatcher p) {
        return new RegistryPorts(events, signer, documents, publisher, consent, privacy, vault, p, visibility, decisions, records, metrics);
    }

    public RegistryPorts withVisibility(VisibilityPort p) {
        return new RegistryPorts(events, signer, documents, publisher, consent, privacy, vault, matcher, p, decisions, records, metrics);
    }

    public RegistryPorts withDecisions(DecisionPort p) {
        return new RegistryPorts(events, signer, documents, publisher, consent, privacy, vault, matcher, visibility, p, records, metrics);
    }

    public RegistryPorts withRecords(SystemOfRecordPort p) {
        return new RegistryPorts(events, signer, documents, publisher, consent, privacy, vault, matcher, visibility, decisions, p, metrics);
    }

    public RegistryPorts withMetrics(MetricsPort p) {
        return new RegistryPorts(events, signer, documents, publisher, consent, privacy, vault, matcher, visibility, decisions, records, p);
    }

    /**
     * Implemented by default port implementations that are safe only for development (state lost on restart).
     * {@link RegistryModule} refuses to start with one of them on a non-development profile.
     */
    public interface DevelopmentOnly {
        /** Why this instance is development-only and what to configure instead, or null when it is production-safe. */
        String developmentOnlyReason();
    }

    /** The reasons the bound ports are not production-safe (empty when they are). */
    public List<String> developmentOnlyReasons() {
        List<String> out = new java.util.ArrayList<>();
        for (Object p : List.of(events, signer, documents, publisher, consent, privacy, vault, matcher, visibility,
                decisions, records, metrics)) {
            if (p instanceof DevelopmentOnly d && d.developmentOnlyReason() != null) {
                out.add(d.developmentOnlyReason());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ the ports

    /** Registry events, written in the caller's transaction (the integrator maps this to the core Outbox). */
    @FunctionalInterface
    public interface EventPort {
        EventPort NONE = (c, ctx, rootId, type, payload) -> {
        };

        void publish(Connection c, RegistryContext ctx, String rootId, String eventType, Map<String, Object> payload);
    }

    /** A detached signature. */
    public record Signature(String algorithm, String keyId, byte[] value) {
    }

    /** Signing for QR payloads and certified extracts; keys stay in the key store / HSM / eSign service. */
    public interface SignerPort {
        Signature sign(String tenant, String purpose, byte[] data);

        boolean verify(String tenant, String keyId, String algorithm, byte[] data, byte[] signature);

        /** Public key material for offline verifiers (e.g. a JWK or base64 X.509 SubjectPublicKeyInfo), if exposed. */
        default Optional<String> publicKey(String tenant, String keyId) {
            return Optional.empty();
        }
    }

    /** A generated document stored in the content service. */
    public record GeneratedDocument(String documentRef, String sha256Hex, String contentType) {
    }

    /** Document generation from an output template (PDF or PDF/A); the integrator binds it to the content service. */
    @FunctionalInterface
    public interface DocumentPort {
        DocumentPort NONE = (ctx, templateKey, model) -> Optional.empty();

        Optional<GeneratedDocument> render(RegistryContext ctx, String templateKey, Map<String, Object> model);
    }

    /** Push of an issued document to an external locker or wallet; returns the external URI. Idempotent by object id. */
    @FunctionalInterface
    public interface PublishPort {
        PublishPort NONE = (ctx, channel, objectId, documentRef, meta) -> Optional.empty();

        Optional<String> publish(RegistryContext ctx, String channel, String objectId, String documentRef,
                                 Map<String, Object> meta);
    }

    /** Consent decision. */
    public record ConsentDecision(boolean permitted, String reason, String consentRef) {
        public static ConsentDecision permit(String ref) {
            return new ConsentDecision(true, null, ref);
        }

        public static ConsentDecision deny(String reason) {
            return new ConsentDecision(false, reason, null);
        }
    }

    /**
     * Consent and purpose checks; the compliance module implements it. The registry calls {@link #check} before it
     * processes or discloses a party's data for a purpose, and tells the compliance side about merges so consent
     * records follow the golden record.
     */
    public interface ConsentPort {
        ConsentPort PERMIT_ALL = new ConsentPort() {
            @Override
            public ConsentDecision check(RegistryContext ctx, String partyId, String purpose, String processing) {
                return ConsentDecision.permit(null);
            }
        };

        ConsentDecision check(RegistryContext ctx, String partyId, String purpose, String processing);

        default void partiesMerged(RegistryContext ctx, String survivorId, String mergedId) {
        }

        default void partiesUnmerged(RegistryContext ctx, String survivorId, String restoredId) {
        }

        /** Erasure request for a party (the compliance module decides whether it may proceed). */
        default void erasureRequested(RegistryContext ctx, String partyId) {
        }
    }

    /** Purpose-based masking of a view before it leaves the registry. */
    @FunctionalInterface
    public interface PrivacyPort {
        PrivacyPort NONE = (ctx, resourceType, view, sensitiveFields) -> view;

        Map<String, Object> mask(RegistryContext ctx, String resourceType, Map<String, Object> view,
                                 List<String> sensitiveFields);
    }

    /** A protected identifier: a keyed hash for matching and an optional vault token for the clear value. */
    public record ProtectedValue(String hashHex, String vaultRef, String masked) {
    }

    /** Sensitive identifier protection (vault tokens and keyed hashes). */
    public interface IdentifierVault {
        /** Protects a normalised value: the hash is deterministic per tenant and type, so matching still works. */
        ProtectedValue protect(String tenant, String idType, String normalisedValue, boolean storeInVault);

        /** The clear value of a vault token, for an authorised and audited purpose. */
        Optional<String> reveal(String tenant, String vaultRef, String purpose);
    }

    /** A score from an external probabilistic matcher. */
    public record ExternalScore(String candidatePartyId, double score, Map<String, Object> details) {
    }

    /** Optional external probabilistic matcher (for example a worker that runs a trained model). */
    @FunctionalInterface
    public interface ExternalMatcher {
        ExternalMatcher NONE = (ctx, probe, candidates) -> List.of();

        List<ExternalScore> score(RegistryContext ctx, Map<String, Object> probe, List<Map<String, Object>> candidates);
    }

    /**
     * Row visibility for list and get queries: the security module's compiled predicate over the given table alias
     * (columns {@code tenant_id} and {@code id}). The default limits rows to the caller's tenant only.
     */
    @FunctionalInterface
    public interface VisibilityPort {
        VisibilityPort TENANT_ONLY = (ctx, resourceType, alias) ->
                new SqlPredicate(alias + ".tenant_id = ?", List.of(ctx.tenant()));

        SqlPredicate visible(RegistryContext ctx, String resourceType, String alias);
    }

    /** DMN evaluation: output rows for the input variables. */
    @FunctionalInterface
    public interface DecisionPort {
        DecisionPort NONE = (tenant, key, vars) -> List.of();

        List<Map<String, Object>> evaluate(String tenant, String decisionKey, Map<String, Object> variables);
    }

    /**
     * Facade mode: an external system of record holds the master copy. The registry mirrors it and pushes changes
     * made locally, so the same flows work when the registry is not the master.
     */
    public interface SystemOfRecordPort {
        SystemOfRecordPort NONE = new SystemOfRecordPort() {
            @Override
            public Optional<Map<String, Object>> fetch(RegistryContext ctx, String typeKey, String externalRef) {
                return Optional.empty();
            }

            @Override
            public void push(RegistryContext ctx, String typeKey, String objectId, String externalRef, String change,
                             Map<String, Object> view) {
            }
        };

        Optional<Map<String, Object>> fetch(RegistryContext ctx, String typeKey, String externalRef);

        void push(RegistryContext ctx, String typeKey, String objectId, String externalRef, String change,
                  Map<String, Object> view);
    }

    /** Metrics bridge. */
    public interface MetricsPort {
        MetricsPort NONE = new MetricsPort() {
        };

        /** Time spent waiting for a gap-free counter row lock. */
        default void counterLockWait(String tenant, String seriesKey, long nanos) {
        }

        default void increment(String name, Map<String, String> tags) {
        }
    }
}
