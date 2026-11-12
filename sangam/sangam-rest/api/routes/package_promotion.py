# SPDX-License-Identifier: Apache-2.0
# M-074 (P2-013; SANGAM.md R43, X.2.4.5, XII.4 #5, T70): signed, hash-addressed executable packages moved between
# environments as the same bytes, with the ENV_BINDING gate. Contract-first: the server side (PackagePort and adapter,
# PACKAGE_SIGNATURE and ENV_BINDING tables, reuse of the onboarding BundleSigner) belongs to the owning lanes.
# Keys (owner decision Q-04): separate Ed25519 keys per purpose and environment through the secret provider; this
# contract carries key ids only, never keys. package_hash = SHA-256(JCS(manifest without signatures)) (X.2.4.5).
# Development profiles may allow unsigned packages (a server flag); the import and verify calls then report
# signed=false instead of refusing.

schema("PackageSignature", "A signature over the package hash by one lifecycle role (PACKAGE_SIGNATURE, X.2.4.5).",
       "packageHash role principalId seatId alg keyId signature signedAt:dt",
       enums={"role": ["author", "reviewer", "approver", "deployer"], "alg": ["Ed25519"]},
       descs={"signature": "Base64 signature over the raw 32-byte package hash", "keyId": "Id of the signing key "
              "(per purpose and environment, Q-04); the key itself never leaves the secret provider"})
schema("PackageBundle", "A package exported for another environment or an air-gapped install (T50): the manifest, every "
                        "artifact by path and the signatures. The target recomputes the hash; nothing is rebuilt.",
       "format:int packageHash manifest:obj artifacts:smap signatures:list:PackageSignature sourceEnvironment "
       "exportedAt:dt", ["format", "packageHash", "manifest", "artifacts"],
       descs={"artifacts": "Artifact path → content: UTF-8 text for model, form, JSON, XML and YAML files, base64 otherwise "
                           "(as Package.resources)",
              "manifest": "The package manifest (X.2.4.5 sketch): package, profile, artifacts with sha256, bindings, "
                          "rulesets, contracts, policies, envBindings (names only)"})
schema("SignatureCheck", "Verification of one signature.", "role keyId valid:bool reason",
       enums={"reason": ["OK", "BAD_SIGNATURE", "UNKNOWN_KEY", "KEY_REVOKED", "ROLE_MISSING"]})
schema("PackageVerification", "Verification of a bundle against this environment, without importing it (T70).",
       "packageHash recomputedHash hashMatches:bool signed:bool signatures:list:SignatureCheck "
       "artifactMismatches:list:s envBindingsRequired:list:s envBindingsMissing:list:s ok:bool",
       descs={"artifactMismatches": "Artifact paths whose bytes do not match the sha256 in the manifest",
              "envBindingsMissing": "Bindings the manifest names that have no APPROVED ENV_BINDING here; activation is "
                                    "blocked until they exist"})
schema("PackageImportRequest", "Import a verified bundle as the same package in this environment.",
       "bundle:PackageBundle reason", ["bundle"])
schema("EnvBinding", "An environment value a package references by name (ENV_BINDING, X.2.4.5): an endpoint or a "
                     "secret reference, never a secret value.",
       "tenantId env name version:int valueRef state createdBy createdAt:dt approvedBy approvedAt:dt reason",
       enums={"state": ["PENDING", "APPROVED", "RETIRED"]})
schema("EnvBindingRequest", "A new version of a binding; it needs approval before activation can use it.",
       "env valueRef reason", ["env", "valueRef", "reason"],
       descs={"valueRef": "Endpoint URL or secret-provider reference (e.g. secret://treasury/api-key); never a secret value"})
schema("EnvBindingApproval", "Approval of a pending binding version; in production the approver differs from the "
                             "creator and from the package deployer (X.2.4.5).",
       "env version:int reason", ["env", "version", "reason"])

_PP_REQ = ["R43", "X.2.4.5", "T70"]
op("get", "/packages/{id}/signatures", "listPackageSignatures", "Packages", "Package signatures",
   "Signatures over the package hash, one per lifecycle role that signed (author, reviewer, approver, deployer).",
   _PP_REQ, params=[ID], resp=(200, "array:PackageSignature"), perm="package.read", errors=GET_E)
op("get", "/packages/{id}/export", "exportPackage", "Packages", "Export a package for promotion",
   "The signed bundle of an APPROVED (or later) package, labelled with this environment. 409 INVALID_TRANSITION before "
   "approval.", _PP_REQ + ["T50"], params=[ID], resp=(200, "PackageBundle"), perm="package.deploy",
   errors=("Unauthorized", "Forbidden", "NotFound", "Conflict"))
op("post", "/packages/import/verify", "verifyPackageBundle", "Packages", "Verify a package bundle",
   "Recomputes the package hash and every artifact hash, checks the signatures against this environment's trusted keys "
   "and lists missing ENV_BINDINGs. Changes nothing (air-gapped verification, T50/T70).", _PP_REQ + ["T50"],
   body="PackageBundle", resp=(200, "PackageVerification"), perm="package.read", errors=STD_R)
op("post", "/packages/import", "importPackage", "Packages", "Import a package bundle",
   "Imports the same bytes as an APPROVED package of this tenant after the verification above; deploy and activate "
   "follow the normal lifecycle, and activation is blocked while an ENV_BINDING is missing (409 ENV_BINDING_MISSING). "
   "422 PACKAGE_HASH_MISMATCH, PACKAGE_SIGNATURE_INVALID, PACKAGE_UNSIGNED, PACKAGE_KEY_UNKNOWN; 409 when the same "
   "package hash exists.", _PP_REQ + ["R41", "R42"], body="PackageImportRequest", resp=(201, "Package"),
   cmd="package.import", perm="package.deploy", created=True,
   errors=("BadRequest", "Unauthorized", "Forbidden", "Conflict", "Unprocessable"))
op("get", "/env-bindings", "listEnvBindings", "Packages", "Environment bindings",
   "Bindings of the tenant, latest version per name and environment.", _PP_REQ,
   params=[q("env"), q("name"), q("state", enum=["PENDING", "APPROVED", "RETIRED"])], resp=(200, "array:EnvBinding"),
   perm="package.read", errors=STD_R)
op("put", "/env-bindings/{name}", "setEnvBinding", "Packages", "Set an environment binding",
   "Records a new PENDING version of the binding for env; the previous APPROVED version stays in force until the new one "
   "is approved.", _PP_REQ + ["R41", "R42"], params=[pp("name", "Binding name as the manifest's envBindings lists it")],
   body="EnvBindingRequest", resp=(200, "EnvBinding"), cmd="env-binding.set", perm="package.deploy")
op("post", "/env-bindings/{name}/approve", "approveEnvBinding", "Packages", "Approve an environment binding",
   "Approves a PENDING version (four-eyes: 422 SEGREGATION_OF_DUTIES when the approver created it, or in production "
   "deploys the package). 409 INVALID_TRANSITION when not PENDING.", _PP_REQ + ["R41", "R42"],
   params=[pp("name", "Binding name")], body="EnvBindingApproval", resp=(200, "EnvBinding"), cmd="env-binding.approve",
   perm="package.approve", errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound", "Conflict", "Unprocessable"))
