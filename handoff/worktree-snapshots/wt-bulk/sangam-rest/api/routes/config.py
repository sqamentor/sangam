# SPDX-License-Identifier: Apache-2.0
# sangam-config: layered configuration profiles (maker-checker), master codes, classification, document types,
# crosswalks (ADR-D-20). Executed inside gen_openapi.py.

TAGS.append(("Config", "Configuration profiles with maker-checker lifecycle, master codes, classification schemes, "
                       "document types and crosswalks (sangam-config, ADR-D-20)"))
CFR = ["ADR-D-20", "R43"]

schema("ConfigFinding", "A validation finding.", "severity code path message ruleRef",
       enums={"severity": ["ERROR", "WARNING", "PLACEHOLDER", "INFO"]})
schema("ConfigFindingsResult", "Findings; valid = no ERROR finding.", "valid:bool findings:list:ConfigFinding")
schema("ConfigResolvedProfile", "A profile resolved for a scope (and office) on a date: the merged data, the layer "
       "chain, the binding (stored per case, R43), unverified placeholders and findings.",
       "scope office asOf:date data:obj chain:list:s binding placeholders:smap findings:list:ConfigFinding")
schema("ConfigProfileVersion", "One stored version of a profile document.",
       "id version layer status hash effectiveFrom:date effectiveTo:date source",
       enums={"status": ["DRAFT", "SUBMITTED", "APPROVED", "ACTIVE", "RETIRED", "REJECTED"]})
schema("ConfigProfileVersionDocument", "A version and its document tree.",
       "version:ConfigProfileVersion document:obj")
schema("ConfigApproveRequest", "Checker approval, bound to the hash the checker reviewed.", "presentedHash",
       ["presentedHash"])
schema("ConfigRejectRequest", "Rejection with a reason.", "reason", ["reason"])
schema("ConfigActivateRequest", "Scopes whose resolution must stay valid after activation.", "scopesToCheck:list:s")
schema("ConfigChangeRecord", "One entry of the profile change register.",
       "profileId version action actor at:dt contentHash reason")
schema("ConfigValidateRequest", "Validate the resolved profile of a scope.", "scope office asOf:date")
schema("ConfigMasterCode", "A master code (head of account, object head, ...), effective-dated.",
       "scope scheme segment code parentCode name:smap flags:obj validFrom:date validTo:date sourceRef")
schema("ConfigSeedResult", "Codes loaded.", "count:int")
schema("ConfigSlipChange", "One change of a correction slip.", "op segment parentCode code name:smap flags:obj newCode")
schema("ConfigCorrectionSlip", "A correction slip to a classification scheme (effective-dated).",
       "id scope scheme effectiveFrom:date changes:list:ConfigSlipChange", ["scope", "scheme"])
schema("ConfigClassificationRequest", "Validate a classification code against its scheme.",
       "scope office scheme code asOf:date", ["scheme", "code"])
schema("ConfigDocumentCheckRequest", "Check a document against its document type.",
       "scope office document:obj asOf:date")
schema("ConfigCrosswalkRequest", "Map a code (and amount) through a crosswalk.", "scope office code amount asOf:date",
       ["code"])

YAML_BODY = {"required": True, "content": {
    "application/yaml": {"schema": {"type": "string"}},
    "application/json": {"schema": {"type": "string"}},
    "text/plain": {"schema": {"type": "string"}}}}
TEXT_BODY = {"required": True, "content": {
    "text/csv": {"schema": {"type": "string"}},
    "application/yaml": {"schema": {"type": "string"}},
    "text/plain": {"schema": {"type": "string"}}}}
PID = pp("id", "profile id (a scope id such as RJ.PWD, an office overlay RJ.PWD/DIV-1, or a shared document)")
VER = pp("version")
SCOPE = q("scope", desc="scope id; default: the caller's tenant")

op("get", "/config/profiles", "resolveConfigProfile", "Config", "Resolve the profile of a scope",
   "Merges the layer chain (international > regime > jurisdiction > category > government > department > office) "
   "for the scope and date.", CFR, params=[SCOPE, q("office"), q("asOf", "date")], resp=(200, "ConfigResolvedProfile"),
   perm="config.read")
op("get", "/config/profiles/{id}/versions", "listConfigProfileVersions", "Config", "List the versions of a profile",
   "Every stored version with its status and content hash.", CFR, params=[PID],
   resp=(200, "array:ConfigProfileVersion"), perm="config.read", list_=True)
op("get", "/config/profiles/{id}/versions/{version}", "getConfigProfileVersion", "Config", "Get one profile version",
   "The version's status, hash and document tree.", CFR, params=[PID, VER], resp=(200, "ConfigProfileVersionDocument"),
   perm="config.read")
op("put", "/config/profiles/{id}/versions/{version}", "saveConfigProfileDraft", "Config", "Save a draft version",
   "Maker step: the body is the profile document (YAML or JSON); its id and version must match the path. A version "
   "that left DRAFT/REJECTED is frozen (409 VERSION_FROZEN). Writes are limited to the caller's own scope.",
   CFR, params=[PID, VER], body=YAML_BODY, resp=(200, "ConfigFindingsResult"), cmd="config.profile.save",
   perm="config.write")
op("post", "/config/profiles/{id}/versions/{version}/submit", "submitConfigProfile", "Config", "Submit for approval",
   "Maker submits the draft; the content hash is frozen.", CFR, params=[PID, VER], resp=(204, None),
   cmd="config.profile.submit", perm="config.write")
op("post", "/config/profiles/{id}/versions/{version}/approve", "approveConfigProfile", "Config", "Approve a version",
   "Checker approval bound to the reviewed hash (409 APPROVAL_INVALIDATED if it changed); checker must differ from "
   "the maker (422 SOD_VIOLATION).", CFR + ["K5"], params=[PID, VER], body="ConfigApproveRequest", resp=(204, None),
   cmd="config.profile.approve", perm="config.admin")
op("post", "/config/profiles/{id}/versions/{version}/reject", "rejectConfigProfile", "Config", "Reject a version",
   "Checker rejection with a reason.", CFR, params=[PID, VER], body="ConfigRejectRequest", resp=(204, None),
   cmd="config.profile.reject", perm="config.admin")
op("post", "/config/profiles/{id}/versions/{version}/activate", "activateConfigProfile", "Config", "Activate a version",
   "Activates an approved version after re-resolving the listed scopes (422 ACTIVATION_REFUSED on errors).", CFR,
   params=[PID, VER], body="ConfigActivateRequest", body_required=False, resp=(200, "ConfigFindingsResult"),
   cmd="config.profile.activate", perm="config.admin")
op("get", "/config/profiles/{id}/history", "getConfigProfileHistory", "Config", "Profile change register",
   "Every save, submission, approval, rejection, activation and import.", CFR, params=[PID],
   resp=(200, "array:ConfigChangeRecord"), perm="config.read", list_=True)
op("post", "/config/validate", "validateConfigProfile", "Config", "Validate a resolved profile",
   "Validates the resolved profile of a scope (required values, placeholders, locks).", CFR,
   body="ConfigValidateRequest", resp=(200, "ConfigFindingsResult"), perm="config.read")
op("get", "/config/master/{scheme}/{segment}", "listConfigMasterCodes", "Config", "List master codes",
   "Codes of a scheme segment valid on the date (optionally children of a parent code).", CFR,
   params=[pp("scheme"), pp("segment"), SCOPE, q("parent"), q("asOf", "date")], resp=(200, "array:ConfigMasterCode"),
   perm="config.read", list_=True)
op("post", "/config/master/seed", "seedConfigMasterCodes", "Config", "Load master codes",
   "Loads codes from a CSV (header scheme,segment,parent,code,name_en,flags,validFrom,validTo,source,status) or YAML "
   "body; upsert by key, so a repeated load is harmless.", CFR,
   params=[SCOPE, q("format", desc="csv (default) or yaml", enum=["csv", "yaml"])], body=TEXT_BODY,
   resp=(200, "ConfigSeedResult"), cmd="config.master.seed", perm="config.write")
op("post", "/config/master/correction-slips", "applyConfigCorrectionSlip", "Config", "Apply a correction slip",
   "Adds, renames, closes or re-codes classification codes with an effective date.", CFR,
   body="ConfigCorrectionSlip", resp=(200, "ConfigFindingsResult"), cmd="config.master.slip", perm="config.write")
op("post", "/config/classification/validate", "validateConfigClassification", "Config", "Validate a classification code",
   "Parses the code against its scheme, checks every segment and the combination rules; returns the derived "
   "attributes.", CFR, body="ConfigClassificationRequest", resp=(200, "object"), perm="config.read")
op("get", "/config/document-types", "listConfigDocumentTypes", "Config", "The document-type catalogue",
   "Document types of the scope's profile (optionally one family).", CFR,
   params=[SCOPE, q("family"), q("asOf", "date")], resp=(200, {"application/json": {"schema": {
       "type": "array", "items": {"type": "object", "additionalProperties": True}}}}), perm="config.read", list_=True)
op("post", "/config/document-types/{id}/check", "checkConfigDocument", "Config", "Check a document",
   "Checks a document against its type (required fields, limits, chain).", CFR, params=[pp("id")],
   body="ConfigDocumentCheckRequest", resp=(200, "object"), perm="config.read")
op("post", "/config/crosswalks/{id}/map", "mapConfigCrosswalk", "Config", "Map through a crosswalk",
   "Maps a code (and amount) to the target classification (e.g. COFOG) with shares.", CFR, params=[pp("id")],
   body="ConfigCrosswalkRequest", resp=(200, "object"), perm="config.read")
