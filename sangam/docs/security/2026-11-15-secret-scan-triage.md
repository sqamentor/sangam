# Secret-scan triage, 2026-11-15

**Status: PROVISIONAL.** The conclusion below rests on a manual review of the alerts GitHub held on 2026-11-15. It
becomes final only when a gitleaks run with `.gitleaks.toml` on `main` is recorded here with its result. If a real
secret is found at any time, it must be revoked and replaced; removing or archiving the file is not remediation.

## Scope

All 125 open code-scanning alerts of the tool Gitleaks on `sqamentor/sangam` (analysis of `main`, history-wide scan,
gitleaks 8.30.1 default rules, no configuration file at the time): `generic-api-key` 105, `curl-auth-header` 16,
`private-key` 4. 36 were in the active tree and 89 in `handoff/` (now archived, see `ARCHIVE.md`).

## Method

1. The flagged line and columns of every alert were read in the tree.
2. For the 36 active-tree alerts in files that had changed since the scan, the same line and column were replayed against
   every historical version of the file (the scan is history-wide, so a line number can point at older text).
3. The tracked tree was searched for PEM private-key blocks and for well-known token prefixes (GitHub, AWS, Slack,
   Anthropic, Google, PGP).
4. GitHub secret scanning (provider patterns, push protection on) was read: 0 open, 0 resolved alerts. Push protection
   blocked one push on 2026-11-14: a third-party page saved under a scratch folder embedded that site's own public map
   token; the page was removed from the commit before anything reached GitHub.

## Result

No credential was found. What the alerts point at:

| Group | Alerts | What it is |
|---|---|---|
| `private-key`, active tree | 130, 131, 132, 133 | The PEM header text in a parser, a simulator and two tests; the key is generated at run time (`KeyPairGenerator`) |
| Fidelius vectors | 113-128 | Curve25519 known-answer test vectors of the ABDM reference CLI (`FideliusVectorsTest` asserts the recorded copy) |
| Key-reference tests | 134, 136 | The fixed 21-character ASN.1 prefix of any Ed25519 PKCS#8 key; contains no key byte |
| Digests | 112, 135 | A 40-hex action commit SHA; a SHA-256 of a generated BPMN model |
| Model identifiers | 129, 166-173 | Process keys, form keys, a routing decision key, a simulator receipt reference |
| `spike/` | 175, 176 | A process activity id; a jar file name |
| Dev-token placeholder | 174 | The documented development token placeholder in the server README |
| `handoff/` (archived) | 52-111, 137-165 (89) | Copies of the same shapes, plus SHA-256 manifests, hash logs, test-run ids and a copy of the test vectors |

## Limits of this review

- **Fidelius vectors:** not compared with the upstream repository; the in-repository licence file and the byte-exact test
  were relied on. Confidence about 95%.
- **Development token:** `application-h2.yaml` ships a well-known placeholder token with administrator groups under the
  `h2` profile. `SecurityConfig` refuses it outside development (decision 152); that guard was not audited end to end in
  this review.
- **Archive:** the 89 `handoff/` findings were classified by group, each file shape read at least once; they were not
  re-read one by one after archiving.

## Scanner runs

| Check | Where | Result |
|---|---|---|
| GitHub secret scanning (provider patterns) | GitHub, `main` | 0 open alerts on 2026-11-15 |
| gitleaks with `.gitleaks.toml` | local | NOT_RUN (gitleaks is not installed on the authoring machine) |
| gitleaks with `.gitleaks.toml` | CI, `security.yml` | NOT_RUN at the time of writing; record the run id and finding count here |

## Configuration that follows from it

`.gitleaks.toml` at the repository root: default rules on; one allowlist per group above, each tied to a rule, to named
files and to the exact shape seen. The archived folder is covered only in the two commits that added it. No rule is
disabled and no active path is excluded as a whole.
