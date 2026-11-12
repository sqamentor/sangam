# SPDX-License-Identifier: Apache-2.0
# SL-40: tenant custom fields on forms and registered object types (sangam-rest TenantCustomFieldRegistry and
# TenantCustomFieldController; table TENANT_CUSTOM_FIELD). The tenant is the caller's. Executed inside gen_openapi.py.

CF = ["SL-40", "R47"]
CF_W = CF + ["R41", "R42"]
CF_PATH = [pp("objectType", "form:<formKey> or a registered object type"), pp("key", "x_<name>")]

schema("CustomField", "One effective-dated version of a tenant custom field; effectiveTo is exclusive (null: open-ended).",
       "objectType key version:int label required:bool readOnly:bool schema:obj effectiveFrom:date effectiveTo:date",
       descs={"schema": "Scalar or array fragment in the Sangam form-data profile of JSON Schema (FormSchemaValidator)"})
schema("CustomFieldDefineRequest", "A new version of a field; it closes the version in force on its effectiveFrom.",
       "objectType key label required:bool readOnly:bool schema:obj effectiveFrom:date effectiveTo:date",
       ["objectType", "key", "schema", "effectiveFrom"])
schema("CustomFieldRetireRequest", "Closes the current version on effectiveTo (exclusive); the reason is audited.",
       "effectiveTo:date reason", ["effectiveTo", "reason"])

op("get", "/custom-fields", "listCustomFields", "Forms", "List custom fields",
   "Every version of the tenant's custom fields, by object type, key and version; `on` keeps the versions in force on "
   "that date. 400 VALIDATION_FAILED for a malformed date.", CF, list_=True,
   params=[q("objectType"), q("on", "date", "only the versions in force on this date")],
   resp=(200, "array:CustomField"), perm="form.read", errors=STD_R)
op("get", "/custom-fields/{objectType}/{key}", "getCustomFieldVersions", "Forms", "Custom field versions",
   "Every version of one field, oldest first. 404 NOT_FOUND when the key was never defined.", CF,
   params=CF_PATH, resp=(200, "array:CustomField"), perm="form.read", errors=GET_E)
op("post", "/custom-fields", "defineCustomField", "Forms", "Define a custom field version",
   "Adds a new effective-dated version (the previous one is closed on its start date; history is never rewritten), "
   "audited (CUSTOM_FIELD_DEFINED). 400 VALIDATION_FAILED; 422 CUSTOM_FIELD_INVALID (key, schema profile, dates, "
   "object type not extensible); 409 CUSTOM_FIELD_CONFLICT when changed concurrently.", CF_W,
   body="CustomFieldDefineRequest", resp=(201, "CustomField"), cmd="custom-field.define", perm="tenant.admin",
   created=True)
op("post", "/custom-fields/{objectType}/{key}/retire", "retireCustomField", "Forms", "Retire a custom field",
   "Closes the current version on effectiveTo, audited (CUSTOM_FIELD_RETIRED); values already stored stay readable. "
   "400 REASON_REQUIRED; 404 NOT_FOUND; 422 CUSTOM_FIELD_INVALID; 409 CUSTOM_FIELD_CONFLICT.", CF_W,
   params=CF_PATH, body="CustomFieldRetireRequest", resp=(200, "CustomField"), cmd="custom-field.retire",
   perm="tenant.admin")
