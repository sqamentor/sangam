# Cryptography statement

This page lists the cryptography Sangam's own code uses, derived from the source at the commit it was written on.
Every item cites the file and line that implements it (paths are relative to `sangam/`). It does not describe
third-party products Sangam runs next to (Keycloak, the database, the ingress controller); where Sangam relies on them,
that is stated. Sangam implements no cryptographic primitive itself: all primitives come from the JDK providers
(JCA/JCE), Bouncy Castle (where named) and, in the browser, WebCrypto.

Security reports: [SECURITY.md](../../../SECURITY.md). Threat model: [THREAT-MODEL.md](../THREAT-MODEL.md).

## 1. Algorithms and key lengths

### Encryption at rest

| Use | Algorithm | Key / parameters | Evidence |
|---|---|---|---|
| Field encryption (per-tenant data keys, crypto-shredding) | AES-256-GCM, 96-bit random nonce, 128-bit tag, AAD binds tenant, key id and purpose | 256-bit data key from `SecureRandom` | `sangam-security/src/main/java/org/sangam/security/crypto/AesGcmFieldEncryptor.java:21-33,51-53`; `JdbcTenantKeyStore.java:174-175` |
| Wrapping of tenant data keys by the key-encryption key (KEK) | AES-GCM, 128-bit tag, AAD `sangam-dek:<keyId>` | KEK must be exactly 256 bits, else refused | `JdbcTenantKeyStore.java:201-208`; `MasterKeyProvider.java:85-88` |
| National-id vault | the field encryptor above; lookup by HMAC-SHA256 with a pepper | as above | `sangam-compliance/src/main/java/org/sangam/compliance/identity/EncryptedNationalIdVault.java:26,123-124` |
| Exported secret bundles (`SecretAdmin`) | AES-256-GCM, 128-bit tag, 12-byte IV, key from PBKDF2-HMAC-SHA256 | 600,000 iterations, 16-byte random salt, 256-bit derived key | `sangam-ops/src/main/java/org/sangam/ops/secrets/SecretAdmin.java:40,127-137,158-160` |
| Field app offline queue (server side contract) | AES-256-GCM | 256-bit key, 12-byte IV, 128-bit tag | `sangam-field/src/main/java/org/sangam/field/sync/LocalQueueContract.java:39-42`; `LocalQueueCipher.java:38-39,56` |
| Browser offline queue (UI) | AES-GCM (non-extractable WebCrypto key), ECDSA P-256 device key | 96-bit IV, 128-bit tag | `sangam-ui/src/lib/offline/crypto.ts:4,54,59,64` |
| CLI token store | AES-GCM | 12-byte IV, 128-bit tag | `tools/sangam-cli/src/main/java/org/sangam/cli/TokenStore.java:64-65,217` |

### Message authentication (HMAC)

| Use | Algorithm | Minimum key | Evidence |
|---|---|---|---|
| Compliance principal key (pseudonymous principal ids) | HMAC-SHA256 | 32 bytes, shorter refused | `sangam-compliance/src/main/java/org/sangam/compliance/PrincipalKey.java:29-30,40-41` |
| Inbound webhooks (generic channel, messengers, enterprise connectors) | HMAC-SHA256 | set by the provider | `sangam-flowable-ports/src/main/java/org/sangam/ports/events/channel/WebhookInboundEndpoint.java:95-96`; `sangam-channels/src/main/java/org/sangam/channels/messenger/WebhookVerifiers.java:36-37,83-84`; `sangam-channels/src/main/java/org/sangam/channels/enterprise/EnterpriseConnectors.java:116-117` |
| Payment gateway callbacks | HMAC-SHA256 | set by the gateway | `sangam-integration/src/main/java/org/sangam/integration/connector/gov/PaymentGatewayCallbackVerifier.java:251-252` |
| AWS SigV4 (S3 store, event channels) | HMAC-SHA256 / SHA-256 | AWS keys | `sangam-flowable-ports/src/main/java/org/sangam/ports/events/channel/AwsSigV4.java:160-169`; `sangam-flowable-ports/src/main/java/org/sangam/ports/content/S3DocumentStore.java:195-204` |

### Signatures

| Use | Algorithms | Minimum key | Evidence |
|---|---|---|---|
| Signature policy for eSign responses and related verification | RSA / ECDSA with SHA-256/384/512 | RSA at least 2048 bits (a lower configured value is raised to 2048) | `sangam-integration/src/main/java/org/sangam/integration/signature/SignaturePolicy.java:59`; `sangam-esign/src/main/java/org/sangam/esign/EsignResponseVerification.java:66,360-361` |
| eSign request signing (XML-DSig) | RSA-SHA256/384/512; the key must be RSA | not checked in code | `sangam-integration/src/main/java/org/sangam/integration/connector/gov/EsignRequestSigner.java:50-51,79-80` |
| DSC CMS signatures | SHA256withRSA, SHA256withECDSA | not checked in code | `sangam-esign/src/main/java/org/sangam/esign/DscCms.java:58` |
| Entra ID token validation | SHA256withRSA | RSA at least 2048 bits | `sangam-integration/src/main/java/org/sangam/integration/publish/cloud/EntraTokenValidator.java:61,255,258` |
| Teams / Bot Framework tokens | RS256 only (other `alg` refused) | not checked in code | `sangam-channels/src/main/java/org/sangam/channels/messenger/WebhookVerifiers.java:123,130` |
| SMART backend auth / CDS Hooks (health) | RS384 or ES384 (P-384) | not checked in code | `sangam-connectors-health/src/main/java/org/sangam/connectors/health/fhir/SmartBackendAuth.java:29,170`; `.../cdshooks/CdsHooksConnector.java:221` |
| PFMS file signing | SHA256withRSA (PKCS#12 key) or HMAC-SHA256 | not checked in code | `sangam-integration/src/main/java/org/sangam/integration/connector/gov/PfmsFileConnector.java:96-97,348` |
| Bundles, extension catalogue, history archives | Ed25519 | fixed by the algorithm | `sangam-onboarding/src/main/java/org/sangam/onboarding/bundle/BundleSigner.java:53`; `sangam-modeller/src/main/java/org/sangam/modeller/catalogue/CatalogueIndex.java:97`; `sangam-ops/src/main/java/org/sangam/ops/archive/HistoryArchives.java:598` |
| Field evidence packets | ECDSA (default), Ed25519 | not checked in code | `sangam-field/src/main/java/org/sangam/field/evidence/PacketHasher.java:83-85` |
| ABDM Fidelius (health data exchange) | ECDH on curve25519 (Bouncy Castle), HKDF | fixed by the curve | `sangam-connectors-health/src/main/java/org/sangam/connectors/health/fidelius/Fidelius.java:42,64-65,256` |

### Hashes

SHA-256 is the hash for content digests and integrity (for example
`sangam-flowable-ports/src/main/java/org/sangam/ports/content/FileSystemDocumentStore.java:93`,
`sangam-esign/src/main/java/org/sangam/esign/PadesSigning.java:259`). For the SHA-1 and MD5 uses see section 5.

**Known gap.** Minimum key lengths are enforced in code only where the table says so. For the rows marked "not
checked in code" the key comes from the operator or the external party, and its length is whatever the JDK provider
accepts.

## 2. TLS and forward secrecy

- **Inbound (server, UI).** The Sangam server does not terminate TLS itself: `sangam-server` sets no `server.ssl.*`
  properties, and TLS is left to the ingress (see also threat model TM-4). The Helm chart's ingress has
  `ingress.tls.enabled: true` by default (`deploy/helm/sangam/values.yaml:740-742`,
  `deploy/helm/sangam/templates/ingress.yaml:39-42`). TLS protocol versions, cipher suites and therefore forward
  secrecy on the public endpoint are set by the operator's ingress controller or load balancer, not by Sangam.
- **Outbound connectors.** `TlsConfig` enables TLS 1.3 and TLS 1.2 by default and turns on host-name verification
  (`HTTPS` endpoint identification); mutual TLS is supported with a PKCS#12 key store
  (`sangam-integration/src/main/java/org/sangam/integration/connector/TlsConfig.java:22,53,61-64`). Cipher suites are
  not overridden, so they are the JDK defaults. TLS 1.3 cipher suites always use ephemeral key exchange; for TLS 1.2,
  forward secrecy depends on the suite negotiated from the JDK default list.
- **Sidecars (PDF validator, OCR).** Server TLS from a PKCS#12 key store; protocols and suites are JDK defaults
  (`sangam-integration/src/main/java/org/sangam/integration/sidecar/SidecarServer.java:260-268`).

## 3. Random numbers

Keys, nonces, IVs, salts and one-time codes come from `java.security.SecureRandom`, for example:
`AesGcmFieldEncryptor.java:31,50`, `JdbcTenantKeyStore.java:33,174-175` (both in
`sangam-security/src/main/java/org/sangam/security/crypto/`), `sangam-ops/src/main/java/org/sangam/ops/secrets/SecretAdmin.java:129-133`,
`sangam-compliance/src/main/java/org/sangam/compliance/rights/PrincipalOtpChallenges.java:94`,
`sangam-esign/src/main/java/org/sangam/esign/TimestampAuthorityClient.java:50`,
`sangam-esign/src/main/java/org/sangam/esign/RevocationChecker.java:67`,
`sangam-field/src/main/java/org/sangam/field/sync/LocalQueueCipher.java:23`. In the browser, WebCrypto generates the
keys (`sangam-ui/src/lib/offline/crypto.ts:54,59`).

`ThreadLocalRandom` is used only for retry jitter, not for security values
(`sangam-core/src/main/java/org/sangam/core/layer/IdSource.java:34,51`).

## 4. Passwords

Sangam stores no user passwords. The server is an OAuth 2.0 resource server that validates JWTs from the configured
OIDC issuer; HTTP Basic and form login are disabled
(`sangam-server/src/main/java/org/sangam/server/config/SecurityConfig.java:73-77,156-160`). Password storage and
hashing are delegated to the identity provider (Keycloak in the reference deployment). The generated reference realm
sets the password policy `length(12) and notUsername` and does not override Keycloak's hashing algorithm
(`deploy/compose/keycloak/generate_realm.py:331`). Passphrases used to seal secret bundles are stretched with PBKDF2
(section 1) and are not stored.

## 5. SHA-1 and MD5: interoperability and identifiers only

These uses are not for secrecy or for collision-resistant integrity:

- **UUIDv5 (SHA-1), RFC 9562 section 5.5.** Deterministic idempotency identifiers (`operationId`) derived from public
  inputs; the version is fixed by the RFC (`sangam-core/src/main/java/org/sangam/core/layer/Hashes.java:33-45`).
- **WeChat signatures (SHA-1).** WeChat's protocol defines `signature` and `msg_signature` as SHA-1 over the sorted
  token, timestamp and nonce; Sangam verifies them in constant time because the provider requires it
  (`sangam-channels/src/main/java/org/sangam/channels/messenger/WebhookVerifiers.java:56-65`,
  `WeChatCrypto.java:25,56-60`). The WeChat encrypted mode is AES-256-CBC with the IV taken from the key, also as
  the provider defines it (`WeChatCrypto.java:22-23,95,147`); it is used only for that channel.
- **UUIDv3 (MD5) via `UUID.nameUUIDFromBytes`.** Deterministic identifiers and UUIDv5 namespace values, not secrets
  (`sangam-integration/src/main/java/org/sangam/integration/telemetry/TelemetryRuleHandler.java:337`,
  `sangam-rest/src/main/java/org/sangam/rest/modules/config/ModuleConnectors.java:47`,
  `sangam-server/src/main/java/org/sangam/server/bridge/RegistryPortBridges.java:308`,
  `sangam-server/src/main/java/org/sangam/server/bridge/ResourcesPortBridges.java:163`).
