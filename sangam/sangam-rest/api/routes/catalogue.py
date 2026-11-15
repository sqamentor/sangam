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

