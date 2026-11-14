# SPDX-License-Identifier: Apache-2.0
# M-099 (P3-046, R67): SCIM 2.0 provisioning (RFC 7643 schemas, RFC 7644 protocol) for /scim/v2/Users and /Groups,
# mapped to Sangam persons, seats and postings; it replaces the 501 of directory sync
# (sangam-rest JdbcIdentityAdminAdapter). Contract-first: the controller and the mapping belong to the security and REST
# lanes. SCIM differs from the rest of the API on purpose, because IdP clients follow the RFCs:
#   - media type application/scim+json; errors are SCIM error bodies (RFC 7644 section 3.12), not RFC 9457;
#   - lists use startIndex (1-based) and count, and filter (eq, co, sw on userName, externalId, displayName);
#   - the caller is an IdP service principal (R26) holding its own permission, scim.provision (a security surface with
#     its own role, backlog M-099); every change is audited (USER_PROVISIONED, USER_UPDATED, USER_DISABLED,
#     MEMBERSHIP_CHANGED; X.4.0.3).
# Owner decision 154 (W4Q-07): SCIM manages persons, groups and group memberships only. Postings (seats held, IX.5)
# are shown read-only in the Sangam extension schema; postings and seats are read-only in SCIM: a request that changes
# them (POST, PUT or PATCH) is 400 with scimType mutability and changes nothing (an unchanged echo of the current list
# is accepted). Groups hold persons only (no nested groups). Postings change only through the admin API (IX.5 rules 1 and 4). SCIM keeps
# its own table (W4-G generator); the code is in sangam-security.
TAGS.append(("SCIM", "SCIM 2.0 user and group provisioning from an identity provider, mapped to persons, seats and "
                     "postings (R67; RFC 7643, RFC 7644)"))
_SCIM_CT = "application/scim+json"
_SCIM_EXT = "urn:sangam:params:scim:schemas:extension:2.0:User"


def _scim_list(item):
    return {"type": "object", "description": "SCIM ListResponse (RFC 7644 section 3.4.2).",
            "required": ["schemas", "totalResults"],
            "properties": {"schemas": {"type": "array", "items": {"type": "string",
                                                                  "enum": ["urn:ietf:params:scim:api:messages:2.0:ListResponse"]}},
                           "totalResults": typ("int"), "startIndex": typ("int"), "itemsPerPage": typ("int"),
                           "Resources": {"type": "array", "items": R(item)}}}


SCHEMAS["ScimMeta"] = {"type": "object", "description": "SCIM resource metadata (RFC 7643 section 3.1).",
                       "properties": {"resourceType": typ("s"), "created": typ("dt"), "lastModified": typ("dt"),
                                      "location": typ("s"), "version": {"type": "string", "description": "ETag"}}}
SCHEMAS["ScimError"] = {"type": "object", "description": "SCIM error (RFC 7644 section 3.12).", "required": ["schemas", "status"],
                        "properties": {"schemas": {"type": "array", "items": {"type": "string", "enum": [
                                           "urn:ietf:params:scim:api:messages:2.0:Error"]}},
                                       "status": {"type": "string", "description": "HTTP status as a string"},
                                       "scimType": {"type": "string", "enum": ["invalidFilter", "tooMany", "uniqueness", "mutability",
                                                                               "invalidSyntax", "invalidPath", "noTarget",
                                                                               "invalidValue", "invalidVers", "sensitive"]},
                                       "detail": typ("s")}}
schema("ScimName", "User name parts.", "formatted familyName givenName middleName")
schema("ScimMultiValue", "A multi-valued attribute entry (emails, phoneNumbers).", "value type primary:bool display")
schema("ScimGroupRef", "A group the user belongs to (read-only on User).", "value display $ref")
schema("ScimPosting", "A seat held by the person (IX.5): effective-dated; kind as in Assignment. Read-only in SCIM.",
       "seatId kind validFrom:date validTo:date", ["seatId"],
       enums={"kind": ["SUBSTANTIVE", "ADDITIONAL_CHARGE", "LEAVE_SUBSTITUTE", "ACTING"]})
schema("ScimSangamUser", "Sangam extension (" + _SCIM_EXT + "): person and postings.",
       "personId orgUnitId postings:list:ScimPosting",
       descs={"personId": "Sangam person id (read-only; set by the server)",
              "postings": "Seats held (IX.5), read-only (decision 154): a request that changes them gets 400 scimType "
                          "mutability (an unchanged echo of the current list is accepted); postings change only through "
                          "the admin API"})
SCHEMAS["ScimSangamUser"]["properties"]["personId"]["readOnly"] = True
SCHEMAS["ScimSangamUser"]["properties"]["orgUnitId"]["readOnly"] = True
SCHEMAS["ScimSangamUser"]["properties"]["postings"]["readOnly"] = True
SCHEMAS["ScimUser"] = {
    "type": "object", "description": "SCIM User (urn:ietf:params:scim:schemas:core:2.0:User) mapped to a Sangam person.",
    "required": ["schemas", "userName"], "additionalProperties": True,
    "properties": {"schemas": {"type": "array", "items": {"type": "string"}}, "id": {"type": "string", "readOnly": True},
                   "externalId": typ("s"), "userName": typ("s"), "name": R("ScimName"), "displayName": typ("s"),
                   "preferredLanguage": typ("s"), "locale": typ("s"), "timezone": typ("s"), "active": typ("bool"),
                   "emails": {"type": "array", "items": R("ScimMultiValue")},
                   "phoneNumbers": {"type": "array", "items": R("ScimMultiValue")},
                   "groups": {"type": "array", "readOnly": True, "items": R("ScimGroupRef")},
                   _SCIM_EXT: R("ScimSangamUser"), "meta": R("ScimMeta")}}
schema("ScimMember", "A group member: a person (User). A member of type Group is 400 invalidValue (no nested groups).",
       "value display $ref type", ["value"], enums={"type": ["User", "Group"]})
SCHEMAS["ScimGroup"] = {
    "type": "object", "description": "SCIM Group (urn:ietf:params:scim:schemas:core:2.0:Group) mapped to a Sangam group "
                                     "(role membership; R67).",
    "required": ["schemas", "displayName"], "additionalProperties": True,
    "properties": {"schemas": {"type": "array", "items": {"type": "string"}}, "id": {"type": "string", "readOnly": True},
                   "externalId": typ("s"), "displayName": typ("s"), "members": {"type": "array", "items": R("ScimMember")},
                   "meta": R("ScimMeta")}}
schema("ScimPatchOperation", "One PATCH operation (RFC 7644 section 3.5.2).", "op path value:any", ["op"],
       enums={"op": ["add", "remove", "replace"]})
SCHEMAS["ScimPatchRequest"] = {"type": "object", "description": "SCIM PatchOp.", "required": ["schemas", "Operations"],
                               "properties": {"schemas": {"type": "array", "items": {"type": "string", "enum": [
                                   "urn:ietf:params:scim:api:messages:2.0:PatchOp"]}},
                                   "Operations": {"type": "array", "items": R("ScimPatchOperation")}}}
SCHEMAS["ScimUserList"] = _scim_list("ScimUser")
SCHEMAS["ScimGroupList"] = _scim_list("ScimGroup")
_SCIM_OBJ = {"type": "object", "additionalProperties": True,
             "description": "SCIM discovery document (RFC 7643 sections 5-7); its shape is fixed by the RFC"}
RESPONSES["ScimError"] = {"description": "SCIM error: 400 invalidFilter/invalidValue/invalidSyntax, 400 mutability (a write of "
                                         "read-only postings, decision 154), 401, 403, 404, "
                                         "409 uniqueness, 412 version mismatch (If-Match)",
                          "content": {_SCIM_CT: {"schema": R("ScimError")}}}

_SCIM_REQ = ["R67", "R26", "IX.5", "R42"]
_SCIM_LIST_P = [q("filter", desc="SCIM filter: eq, co, sw on userName / externalId / displayName (others: 400 invalidFilter)"),
                q("startIndex", "int", "1-based"), q("count", "int", "Page size, at most 200"),
                q("attributes"), q("excludedAttributes")]


def _scim(method, path, opid, summary, desc, body=None, resp=None, status=200, params=(), cmd=None):
    content = {_SCIM_CT: {"schema": R(body)}, "application/json": {"schema": R(body)}} if body else None
    op(method, path, opid, "SCIM", summary, desc, _SCIM_REQ, params=list(params),
       body={"required": True, "content": content} if content else None,
       resp=(status, {_SCIM_CT: {"schema": resp if isinstance(resp, dict) else R(resp)}} if resp else None),
       if_match=False,
       cmd=cmd, perm="scim.provision", errors=())
    o = PATHS[path][method]
    for code in (["400"] if body or params else []) + ["401", "403"] + (["404"] if "{id}" in path else []) + \
            (["409"] if method in ("post", "put", "patch") else []) + (["412"] if method in ("put", "patch", "delete") else []):
        o["responses"][code] = RESP("ScimError")


_SID = pp("id", "SCIM resource id")
_scim("get", "/scim/v2/ServiceProviderConfig", "scimServiceProviderConfig", "Service provider configuration",
      "Supported features: patch, filter (maxResults 200), changePassword false, sort false, etag true, bulk false.",
      resp=_SCIM_OBJ)
_scim("get", "/scim/v2/ResourceTypes", "scimResourceTypes", "Resource types", "User and Group, with the Sangam extension.",
      resp=_SCIM_OBJ)
_scim("get", "/scim/v2/Schemas", "scimSchemas", "Schemas", "Core User and Group schemas and " + _SCIM_EXT + ".",
      resp=_SCIM_OBJ)
for _res, _one in (("Users", "User"), ("Groups", "Group")):
    _scim("get", "/scim/v2/" + _res, "scimList" + _res, "List " + _res.lower(), "Filtered, paged list of the tenant's "
          + _res.lower() + ".", resp="Scim%sList" % _one, params=_SCIM_LIST_P)
    _scim("post", "/scim/v2/" + _res, "scimCreate" + _one, "Create a " + _one.lower(),
          "Creates the " + ("person (USER_PROVISIONED); 400 mutability when postings are sent" if _one == "User" else "group") +
          "; 409 uniqueness on userName / externalId.", body="Scim" + _one, resp="Scim" + _one, status=201,
          cmd="scim." + _one.lower() + ".create")
    _scim("get", "/scim/v2/" + _res + "/{id}", "scimGet" + _one, "Get a " + _one.lower(), "One resource; ETag in meta.version.",
          resp="Scim" + _one, params=[_SID, q("attributes"), q("excludedAttributes")])
    _scim("put", "/scim/v2/" + _res + "/{id}", "scimReplace" + _one, "Replace a " + _one.lower(),
          "Full replacement (If-Match honoured, 412 on a stale version)." +
          (" active=false disables the person (USER_DISABLED) and revokes its API tokens; postings are not "
           "touched (400 mutability when the request changes them)." if _one == "User" else ""),
          body="Scim" + _one, resp="Scim" + _one, params=[_SID], cmd="scim." + _one.lower() + ".replace")
    _scim("patch", "/scim/v2/" + _res + "/{id}", "scimPatch" + _one, "Patch a " + _one.lower(),
          "RFC 7644 PATCH (add, remove, replace)" + (" of members (MEMBERSHIP_CHANGED)" if _one == "Group" else "") + ".",
          body="ScimPatchRequest", resp="Scim" + _one, params=[_SID], cmd="scim." + _one.lower() + ".patch")
    _scim("delete", "/scim/v2/" + _res + "/{id}", "scimDelete" + _one, "Delete a " + _one.lower(),
          ("Deactivates the person (history kept, X.2.3) and removes its SCIM group memberships; postings are "
           "not touched" if _one == "User" else "Removes the group")
          + "; 204.", status=204, params=[_SID], cmd="scim." + _one.lower() + ".delete")
