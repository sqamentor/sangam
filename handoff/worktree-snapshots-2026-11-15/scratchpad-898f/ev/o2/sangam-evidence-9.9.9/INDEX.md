# Sangam 9.9.9: audit evidence pack (R52)

Commit `unknown`, created 2026-11-07T20:06:45Z.

| Directory | Evidence | Use | Files |
|---|---|---|---|
| `01-sbom` | Software bill of materials (CycloneDX) | Component inventory: Maven, npm, images | 1 |
| `02-licence` | Licence report | X.3a LIC-1..11 gate result at this tag | 1 |
| `03-dependency-check` | OWASP Dependency-Check report | Known CVEs; fails at CVSS >= 7 | 1 |
| `04-codeql` | CodeQL summary | Open code-scanning alerts at this ref | 1 |
| `05-tests` | Test reports | Unit tests and the full Tier-1 database matrix | 1 |
| `06-compliance` | sangam-compliance evidence report | DPDP / CERT-In obligation status | 1 |
| `07-config` | Configuration and version manifest | Default chart values, support matrix | 1 |
| `08-native-profile` | Native-light profile build evidence | R7 smoke result | none |

## Scope and limits

- This pack is evidence **from the vendor release pipeline**. It does not replace the audit itself: the audit is
  performed by a CERT-In-empanelled auditor, before hosting, every year and after major changes (GOV-3).
- The compliance report in `06-compliance` was generated from a **fresh development instance** (H2, no tenant data).
  It shows what the product implements. Each department must generate its own report from its production install
  (`GET /api/v1/compliance/reports`), which also covers logs, NTP and hosting evidence that depend on the deployment.
- Deployer evidence not in this pack: penetration test results, hosting and network configuration, 180-day log
  retention (R51), backup and restore records (T26/T27), incident contacts.

## Verifying

```
cosign verify-blob --bundle SHA256SUMS.sigstore.json \
  --certificate-identity-regexp '^https://github.com/.+/\.github/workflows/release\.yml@refs/tags/' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com SHA256SUMS
sha256sum --strict -c SHA256SUMS
```
