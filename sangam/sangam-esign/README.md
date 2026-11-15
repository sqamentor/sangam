# sangam-esign (optional)

Owner decisions 140, 165 and 217 (M-080). The only Sangam module that depends on Bouncy Castle. Nothing depends on it:
the server finds its verifiers through `ServiceLoader` behind the framework-independent interfaces in
`sangam-integration` (`org.sangam.integration.signature`). Without this module there is no Bouncy Castle on the class
path, operations that need verified eSign or DSC stay blocked, and everything else works.

Live eSign also stays blocked until one real ESP sandbox response confirms the CCA eSign 2.1 profile and the
`messageDigest` convention (owner decision 215). That check belongs to the test phase.

| File | Requirements |
|---|---|
| `EsignResponseVerification.java` | decision 140: ESP XML signature over the whole response against configured ESP certificates; binding (`txn`, time window, intended signer); one CMS signer whose signed `messageDigest` is the expected SHA-256 (detached CMS verified with the expected hash as its content digest); SHA-256/384/512 only; key size and usage; validity at signing time; PKIX path to configured anchors only; revocation of the path (OCSP, then CRL) through `HttpTransport`; three outcomes with evidence |
| `RevocationChecker.java` | OCSP with a nonce on every request (issuer or delegated responder, fresh), then CRL (issuer-signed, current); decision 251: a wrongly echoed nonce is refused, an answer without the nonce counts only with a nextUpdate that has not passed; decision 259: an answer whose thisUpdate is older than `sangam.dsc.ocsp-max-age` (default 24h) is never used; plain http only to the `sangam.dsc.revocation-egress` origins; no authentic current answer = UNAVAILABLE, never "good" |
| `DscCms.java` | decision 165: signed attributes for a prepared PDF (content-type, signing-time, `messageDigest` = prepared digest, signingCertificateV2); the token's signature is checked before the detached CMS is assembled |
| `DscSignatureVerification.java` | decision 165: signed-PDF verification: integrity and coverage (`PdfSignatureValidator`), prepared digest, the signer certificate equal to the enrolled, approved one (decision 227: its name is never trusted), then the decision 140 signer checks |
| `DscSigningProvider.java` | decision 165: `DscSigning` for the server flow: `prepare` = `PadesSigning.prepare` (CAdES detached) then `DscCms.toSign`; `complete` = `DscCms.assemble` (token signature, algorithm and signer re-checked) then `PadesSigning.embed`; every refusal carries a reason code |
| `PadesSigning.java` | R57 two-step external signing: `prepare` (incremental update, zero-filled `/Contents`, SHA-256 of the byte ranges) and `embed` (digest re-checked, CMS `messageDigest` must equal it, `ETSI.CAdES.detached` needs ESS signing-certificate-v2) |
| `TimestampAuthorityClient.java` | RFC 3161 signature time-stamp through `HttpTransport` |
| `PdfSignatureValidator.java` | AU-01 structural report per signature (coverage, integrity, time-stamp, chain) plus the PAdES LTV result of every signature and what changed after the last one (`VALIDATION_DATA_ONLY` when only the DSS was added); no online revocation, not a statement of legal validity |
| `PadesLtv.java` | M-080 PAdES B-LT from the embedded material only, nothing fetched: DSS and every VRI entry (certificates, OCSP, CRL), the CMS and the signature time-stamp; signer and TSA paths to a configured anchor at the time-stamp time; an authentic (issuer or delegated responder with ocsp-nocheck; full CRL in scope) and fresh (issued at or after the time-stamp, less 5 min skew) answer for every certificate on both paths; named codes `LTV_NO_TRUST_ANCHORS`, `LTV_NO_TIMESTAMP`, `LTV_NO_DSS`, `LTV_DSS_INVALID`, `LTV_CHAIN_INCOMPLETE`, `LTV_REVOCATION_MISSING`, `LTV_REVOCATION_STALE`, `LTV_REVOKED`; B-LTA document time-stamps and adbe-revocationInfoArchival are not evaluated |

ServiceLoader files: `META-INF/services/org.sangam.integration.signature.EsignResponseVerifier$Factory` and
`...DscSignatureVerifier$Factory` and `...DscSigning$Factory`.

## Dependencies

| Artifact | Version | Licence | Scope |
|---|---|---|---|
| org.bouncycastle:bcpkix-jdk18on, bcutil-jdk18on, bcprov-jdk18on | 1.84 (root pom `version.bouncycastle`) | Bouncy-Castle (MIT style) | compile |
| org.apache.pdfbox:pdfbox | 3.0.8 | Apache-2.0 | compile |
| io.github.sqamentor:sangam-integration | project | Apache-2.0 | compile |

Tests are written and not run in the coding phase. The DSC agent test lives here because it needs a test certificate
from Bouncy Castle; the agent itself (`org.sangam.integration.dsc`) has no Bouncy Castle.
