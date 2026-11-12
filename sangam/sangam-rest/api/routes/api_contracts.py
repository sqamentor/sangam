# SPDX-License-Identifier: Apache-2.0
# M-068 (W3-B 12:23:11Z REQUEST (3), integrator round 2): API contracts (OpenAPI 3.x, AsyncAPI 2.x / 3.x JSON) stored per
# package, backed by org.sangam.integration.connector.contract.ApiContractStore (table API_CONTRACT, changeset 0043-08:
# tenant_id, package_id, contract_id up to 120, version, format, spec_version, document, sha256, created_by,
# created_at) and ContractOperations (the operation list the modeller picker shows; a service task binds one with
# sangam:operationRef = contractId[@version]#operationId). A stored version is immutable: PUT of a changed document is
# the next version, PUT of an identical one (same SHA-256) answers the stored version. The store has no status and no
# delete, so a contract version is never removed while a deployment may pin it.
TAGS.append(("ApiContracts", "OpenAPI / AsyncAPI contracts of a package and their operations (M-068)"))
schema("ApiContractSummary", "One stored contract version (ApiContractStore.ApiContract without the document).",
       "tenantId packageId contractId version:int format specVersion sha256",
       ["packageId", "contractId", "version", "format", "sha256"], enums={"format": ["OPENAPI", "ASYNCAPI"]},
       descs={"specVersion": "The document's openapi or asyncapi field, e.g. 3.1.0 or 2.6.0",
              "sha256": "SHA-256 (hex) of the stored document"})
schema("ApiContract", "One stored contract version with its document (ApiContractStore.ApiContract).",
       "tenantId packageId contractId version:int format specVersion sha256 document:obj",
       ["packageId", "contractId", "version", "format", "sha256", "document"], enums={"format": ["OPENAPI", "ASYNCAPI"]})
schema("ContractOperation", "One operation of a contract (ContractOperations.Operation without the document): method and "
       "path for OpenAPI, action and channel for AsyncAPI; schemas have their local references inlined.",
       "operationId format method path action channel summary requestSchema:obj responseSchemas:obj servers:list:s",
       ["operationId", "format"], enums={"format": ["OPENAPI", "ASYNCAPI"]},
       descs={"responseSchemas": "Response body schema by HTTP status code (OpenAPI only)",
              "requestSchema": "JSON body schema (OpenAPI) or message payload schema (AsyncAPI)"})
_AC = "/packages/{id}/contracts"
_AC_ID = dict(pp("contractId", "Contract id, [A-Za-z0-9_.-], at most 120 characters"),
              schema={"type": "string", "pattern": "^[A-Za-z0-9_.-]{1,120}$"})
_AC_VER = q("version", "int", "A stored version; the latest when absent")
_AC_REQ = ["M-068", "R43"]
op("put", _AC + "/{contractId}", "putApiContract", "ApiContracts", "Store a contract",
   "Stores the OpenAPI 3.x or AsyncAPI 2.x / 3.x JSON document as the next version of the contract, or answers the stored "
   "version when the document is identical (same SHA-256). 400 VALIDATION_FAILED for an invalid contract id, a document "
   "that is not a JSON object, or another format or version.", _AC_REQ, params=[ID, _AC_ID],
   body={"required": True, "content": {"application/json": {"schema": {"type": "object"}}}},
   resp=(200, "ApiContractSummary"), cmd="package.contract.put", perm="package.author")
op("get", _AC, "listApiContracts", "ApiContracts", "Contracts of a package",
   "The latest version of each contract of the package, by contract id, without documents.", _AC_REQ, params=[ID],
   resp=(200, "array:ApiContractSummary"), perm="package.read", errors=GET_E)
op("get", _AC + "/{contractId}", "getApiContract", "ApiContracts", "Get a contract",
   "One version (the latest when version is absent) with its document.", _AC_REQ, params=[ID, _AC_ID, _AC_VER],
   resp=(200, "ApiContract"), perm="package.read", errors=GET_E)
op("get", _AC + "/{contractId}/operations", "listApiContractOperations", "ApiContracts", "Operations of a contract",
   "The operations of one version (the latest when version is absent), in document order, for the modeller's operation "
   "picker (sangam:operationRef = contractId[@version]#operationId).", _AC_REQ, params=[ID, _AC_ID, _AC_VER],
   resp=(200, "array:ContractOperation"), perm="package.read", errors=GET_E)
