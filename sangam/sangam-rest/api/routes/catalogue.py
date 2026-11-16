# SPDX-License-Identifier: Apache-2.0
# M-128 / NG-17, owner decision 380: the platform-wide extension catalogue (sangam-modeller catalogue) and the
# per-tenant install. Executed inside gen_openapi.py. Off unless sangam.modules.modeller.enabled=true.

_CAT = ["R43", "T70", "R56"]
schema("CatalogueExtensionEntry", "One entry of the signed platform-wide extension catalogue index.",
       "id version kind publisher licence sha256 minPlatform artifactPath",
       ["id", "version", "kind", "publisher", "licence", "sha256", "minPlatform", "artifactPath"],
       descs={"kind": "package is installable (a signed package bundle); every other kind is code and is listed only",
              "sha256": "Hex SHA-256 of the artefact bytes",
              "minPlatform": "The oldest Sangam version the entry runs on",
              "artifactPath": "The artefact, relative to the index (a file of the mirror, or a URL of the hosted catalogue)"})
schema("CatalogueInstallRequest", "Why the catalogue entry is installed; kept in the PACKAGE_IMPORTED audit note.", "reason", [])

op("get", "/catalogue", "getExtensionCatalogue", "Modeller", "Extension catalogue",
   "The entries of the platform-wide signed index, from the mirrored files (sangam.modeller.catalogue.index, the "
   "air-gapped form) or the hosted https URL (sangam.modeller.catalogue.url) fetched through the catalogue's egress "
   "allow-list (owner decision 380). The index signature must verify with one of the trusted keys (secret references). "
   "404 when no catalogue is configured; 422 CATALOGUE_SIGNATURE_INVALID, CATALOGUE_FORMAT_INVALID or "
   "CATALOGUE_EGRESS_DENIED (no allow-list entry); 503 CATALOGUE_UNREACHABLE.", _CAT,
   resp=(200, "array:CatalogueExtensionEntry"), perm="package.read",
   errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound", "Unprocessable", "ServiceUnavailable"))
op("post", "/catalogue/{id}/{version}/install", "installCatalogueEntry", "Modeller", "Install a catalogue entry",
   "Installs a kind package entry into the caller's tenant: the catalogue checks first (index signature, licence "
   "allow-list, platform version, artefact SHA-256), then the package import (T70: the bundle's own hash and "
   "signatures; the package lands APPROVED, activation still needs a deployer and the ENV_BINDING values). 404 "
   "CATALOGUE_ENTRY_NOT_FOUND; 422 CATALOGUE_KIND_NOT_INSTALLABLE (code kinds are never installed), "
   "CATALOGUE_CHECK_FAILED, CATALOGUE_ARTIFACT_INVALID, CATALOGUE_ARTIFACT_PATH_INVALID, CATALOGUE_EGRESS_DENIED, or a "
   "package import refusal (PACKAGE_HASH_MISMATCH, signatures); 409 PACKAGE_EXISTS.", _CAT,
   params=[pp("id", "catalogue entry id"), pp("version", "catalogue entry version")], body="CatalogueInstallRequest",
   body_required=False, resp=(201, "Package"), cmd="modeller.catalogue.install", perm="package.deploy", created=True)

# ---- step 2: the hosting side (submit, review by another person, host-signed index, public index routes)
schema("CatalogueSubmissionRequest", "A signed package bundle submitted to the platform-wide catalogue, with its catalogue fields.",
       "id version licence minPlatform bundle:PackageBundle reason", ["id", "version", "licence", "minPlatform", "bundle"],
       descs={"id": "1 to 100 of a-z 0-9 . _ - (a path segment of the hosted artefact URL)",
              "version": "1 to 100 of a-z 0-9 . _ - (a path segment of the hosted artefact URL)",
              "minPlatform": "The oldest Sangam version the package runs on"})
schema("CatalogueReviewRequest", "Why a catalogue entry is approved or rejected (required to reject).", "reason", [])
schema("CatalogueHostedEntry", "A hosted catalogue entry and its review state.",
       "id version kind publisher licence sha256 minPlatform artifactPath state submittedBy reviewedBy reason reviewReason "
       "submittedAt:dt updatedAt:dt",
       ["id", "version", "kind", "publisher", "licence", "sha256", "minPlatform", "artifactPath", "state", "submittedBy"],
       enums={"state": ["SUBMITTED", "APPROVED", "REJECTED"], "kind": ["package"]},
       descs={"publisher": "tenant/principal of the submitter",
              "sha256": "Hex SHA-256 of the exact bundle bytes served at artifactPath",
              "artifactPath": "Relative to the hosted index: artifacts/<id>/<version>"})

_HOST = "Platform-only permission (the server maps it to platform-issuer identities; no tenant persona group holds it). " \
        "404 when hosting is off (sangam.modeller.catalogue.host.enabled)."
op("post", "/catalogue/entries", "submitCatalogueEntry", "Modeller", "Submit a catalogue entry",
   "Submits a signed package bundle to the platform-wide catalogue (decision 380 P4 (i)); kind package only, code jars are "
   "never staged. The bundle must pass this environment's package verification (hash recomputes, artefacts match, valid "
   "signatures), else 422 CATALOGUE_BUNDLE_UNVERIFIED; the entry's SHA-256 is taken over the stored bundle bytes. 422 "
   "CATALOGUE_ENTRY_ID_INVALID; 409 CATALOGUE_ENTRY_EXISTS (a version is never replaced). The entry is SUBMITTED until "
   "another person approves it. " + _HOST, _CAT,
   body="CatalogueSubmissionRequest", resp=(201, "CatalogueHostedEntry"), cmd="modeller.catalogue.submit",
   perm="platform.catalogue.publish", created=True)
op("get", "/catalogue/entries", "listCatalogueEntries", "Modeller", "Hosted catalogue entries",
   "Every hosted entry with its review state, oldest first; needs platform.catalogue.publish or platform.catalogue.review. "
   + _HOST, _CAT, params=[q("state", enum=["SUBMITTED", "APPROVED", "REJECTED"])],
   resp=(200, "array:CatalogueHostedEntry"), perm="platform.catalogue.review")
for _act, _opid, _sum, _d in (
        ("approve", "approveCatalogueEntry", "Approve a catalogue entry", "Adds a SUBMITTED entry to the signed hosted index."),
        ("reject", "rejectCatalogueEntry", "Reject a catalogue entry", "Rejects a SUBMITTED entry; reason is required (400 REASON_REQUIRED).")):
    op("post", "/catalogue/entries/{id}/{version}/" + _act, _opid, "Modeller", _sum,
       _d + " The reviewer must differ from the publisher (422 SEGREGATION_OF_DUTIES); 409 CATALOGUE_ENTRY_NOT_SUBMITTED "
       "when the entry is no longer SUBMITTED. " + _HOST, _CAT,
       params=[pp("id", "catalogue entry id"), pp("version", "catalogue entry version")], body="CatalogueReviewRequest",
       body_required=False, resp=(200, "CatalogueHostedEntry"), cmd="modeller.catalogue." + _act, perm="platform.catalogue.review")

_PUB = " Public (no credentials): a consumer's sangam.modeller.catalogue.url names /api/v1/catalogue/hosted/index.json. " \
       "404 when hosting is off."
op("get", "/catalogue/hosted/index.json", "getHostedCatalogueIndex", "Modeller", "Hosted catalogue index",
   "The index of the approved entries, ordered by id then version; the exact bytes the signature covers." + _PUB, _CAT,
   resp=(200, {"application/json": {"schema": {"type": "string", "format": "binary"}}}), errors=("NotFound",))
public("/catalogue/hosted/index.json", "get")
op("get", "/catalogue/hosted/index.json.sig", "getHostedCatalogueIndexSignature", "Modeller", "Hosted catalogue index signature",
   "Base64 detached Ed25519 signature over the index bytes, by the host's own catalogue key (secret references "
   "sangam.modeller.catalogue.host.signing-key-ref and .host.public-key-ref; decision 37)." + _PUB, _CAT,
   resp=(200, {"text/plain": {"schema": {"type": "string"}}}), errors=("NotFound",))
public("/catalogue/hosted/index.json.sig", "get")
op("get", "/catalogue/hosted/artifacts/{id}/{version}", "getHostedCatalogueArtifact", "Modeller", "Hosted catalogue artefact",
   "The exact bundle bytes of an approved entry (404 for any other state)." + _PUB, _CAT,
   params=[pp("id", "catalogue entry id"), pp("version", "catalogue entry version")],
   resp=(200, {"application/json": {"schema": {"type": "string", "format": "binary"}}}), errors=("NotFound",))
public("/catalogue/hosted/artifacts/{id}/{version}", "get")
