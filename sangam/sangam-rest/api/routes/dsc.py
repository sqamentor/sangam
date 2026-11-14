# SPDX-License-Identifier: Apache-2.0
# Owner decision 165: DSC signing of a task's PDF through the signer's loopback agent (USB token). Shapes as coded by W4-H
# in sangam-server DscSigningController and sangam-integration DscSigningFlow (W4-H REQUEST 2026-11-13T01:24:24Z;
# integrator ruling 2026-11-13T01:26:13Z: an absent module is 501 CAPABILITY_NOT_INSTALLED). Executed inside
# gen_openapi.py.
#
# Two steps. prepare: the server prepares the PDF and returns the CMS signed attributes for the token to sign; it keeps
# the prepared PDF, bound to tenant, task, document id, version and SHA-256, signer principal, signer certificate and a
# 128-bit prepareId (10 minutes by default, sangam.dsc.prepare-ttl). complete: the prepare is consumed once, the signature
# is embedded and verified (decision 140); only VERIFIED_VALID stores the signed PDF as the document's next version.
# Completing the task stays a separate step, gated on the recorded outcome.
#
# Owner decision 227 (W4-H REQUEST 2026-11-13T02:15:49Z, corrected 02:16:03Z): the signer is bound by an enrolled
# certificate that an admin other than the signer approved (DscCertificateController, DscEnrolment); the display name
# and the certificate CN are shown, never trusted. Owner decision 228: the signed PDF is stored by a compare-and-set on
# the document version (DocumentPort.newVersion with an expected version), so a concurrent upload makes complete answer
# 409 DOCUMENT_CHANGED instead of versioning the signed copy after it.

_DSC_REQ = ()
_DSC_B64 = "Base64 (RFC 4648 section 4, padded)"

SCHEMAS["DscPrepareRequest"] = {
    "type": "object", "description": "Step 1 of a DSC signature (decision 165).",
    "required": ["documentId", "certificateChain"],
    "properties": {
        "documentId": {"type": "string", "minLength": 1,
                       "description": "The PDF to sign: a document attached to the task or to its process instance "
                                      "(a task can carry several documents)"},
        "certificateChain": {"type": "array", "minItems": 1, "maxItems": 10,
                             "items": {"type": "string", "description": _DSC_B64 + " DER X.509 certificate, at most "
                                                                                    "16 KiB decoded"},
                             "description": "The token's chain, signer first. The signer certificate must be the "
                                            "caller's APPROVED enrolment (decision 227). Nothing in the chain is "
                                            "trusted: trust comes only from sangam.dsc.trust-anchors at complete"},
    },
}
SCHEMAS["DscPrepareResponse"] = {
    "type": "object", "description": "What the signer's agent signs.",
    "required": ["prepareId", "signedAttributes", "preparedDigestSha256", "algorithm", "expiresAt", "documentId",
                 "documentVersion"],
    "properties": {
        "prepareId": {"type": "string", "pattern": "^[0-9a-f]{32}$",
                      "description": "Single-use handle of the prepared PDF (128-bit random, lower-case hex)"},
        "signedAttributes": {"type": "string", "description": _DSC_B64 + " DER of the CMS signed attributes (SET OF); "
                                                                         "the token signs exactly these bytes"},
        "preparedDigestSha256": {"type": "string", "pattern": "^[0-9a-f]{64}$",
                                 "description": "SHA-256 (lower-case hex) of the prepared PDF's byte range"},
        "algorithm": {"type": "string", "enum": ["SHA256withRSA", "SHA256withECDSA"],
                      "description": "Chosen from the signer key; complete must send the same value"},
        "expiresAt": {"type": "string", "format": "date-time"},
        "documentId": {"type": "string"},
        "documentVersion": {"type": "integer", "format": "int32",
                            "description": "The document version that was prepared"},
    },
}
SCHEMAS["DscCompleteRequest"] = {
    "type": "object", "description": "Step 2 of a DSC signature (decision 165).",
    "required": ["prepareId", "signature", "algorithm", "certificateChain"],
    "properties": {
        "prepareId": {"type": "string", "minLength": 1},
        "signature": {"type": "string", "minLength": 1,
                      "description": _DSC_B64 + " signature of signedAttributes by the token, at most 2048 bytes "
                                                "decoded"},
        "algorithm": {"type": "string", "minLength": 1, "description": "The algorithm returned by prepare"},
        "certificateChain": {"type": "array", "minItems": 1, "items": {"type": "string"},
                             "description": "The same chain as at prepare, signer first (" + _DSC_B64 + " DER, at "
                                            "most 10). A different signer certificate gives VERIFIED_INVALID "
                                            "DSC_SIGNER_CHANGED"},
    },
}
SCHEMAS["DscCompleteResponse"] = {
    "type": "object",
    "description": "The verification outcome (decision 140). It is recorded either way; only VERIFIED_VALID stores the "
                   "signed PDF.",
    "required": ["outcome", "retryable", "evidence", "documentId"],
    "properties": {
        "outcome": {"type": "string", "enum": ["VERIFIED_VALID", "VERIFIED_INVALID", "INDETERMINATE"]},
        "reasonCode": {"type": ["string", "null"],
                       "description": "Why the outcome is not VERIFIED_VALID, for example CERTIFICATE_NOT_ENROLLED "
                                      "(the enrolment was revoked after prepare), SIGNER_MISMATCH (the signer "
                                      "certificate is not the enrolled one), DSC_SIGNER_CHANGED, "
                                      "DSC_ALGORITHM, DSC_SIGNATURE_INVALID, DIGEST_MISMATCH, CERTIFICATE_CHAIN_INVALID, "
                                      "INVALID_INPUT, CMS_INVALID, SIGNATURE_TOO_LARGE, DSC_ASSEMBLY, "
                                      "DSC_COMPLETE_FAILED or a verifier reason (chain, revocation, policy)"},
        "retryable": {"type": "boolean",
                      "description": "True when the same signature may verify later (for example revocation "
                                     "information was unavailable); a new prepare is still needed"},
        "evidence": {"type": "object", "additionalProperties": True,
                     "description": "Verification evidence (policy version, checks, certificate and revocation "
                                    "details), also stored with the signed version"},
        "documentId": {"type": "string"},
        "signedDocumentVersion": {"type": ["integer", "null"], "format": "int32",
                                  "description": "The new document version holding the signed PDF; only when "
                                                 "outcome is VERIFIED_VALID and it was stored"},
    },
}

_DSC_COMMON = (" The task must be assigned to the caller (403 NOT_ASSIGNEE otherwise) and not suspended. Without the "
               "opt-in wiring (sangam.dsc.enabled, which needs the optional module sangam-esign) the call answers 501 "
               "CAPABILITY_NOT_INSTALLED and nothing is prepared or accepted.")

op("post", "/tasks/{id}/dsc/prepare", "prepareDscSignature", "Tasks", "Prepare a DSC signature",
   "Step 1 of a DSC signature (decision 165). Needs task.write and document.read. The document must be a PDF attached "
   "to the task or its process instance, scanned clean, with a recorded SHA-256 that matches its content, at most "
   "50 MiB. The signer is matched by the enrolled certificate (decision 227): the signer certificate of the chain must "
   "be the caller's own enrolment, APPROVED by an admin other than the caller (/me/dsc/certificates); the display name "
   "and the certificate CN are not used to bind the signer." + _DSC_COMMON,
   _DSC_REQ, params=[ID], body="DscPrepareRequest", resp=(200, "DscPrepareResponse"), cmd="dsc.prepare",
   perm="task.write", errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound", "NotImplemented"))
op("post", "/tasks/{id}/dsc/complete", "completeDscSignature", "Tasks", "Complete a DSC signature",
   "Step 2 of a DSC signature (decision 165). Needs task.write and document.write. Consumes the prepare once (by the "
   "same caller, for the same task, before it expires), embeds and verifies the signature and answers the outcome. "
   "A wrong chain, signature or algorithm is answered 200 with outcome VERIFIED_INVALID or INDETERMINATE, not as an "
   "error; the prepare is used up either way. The enrolment is checked again: if it was revoked after prepare the "
   "outcome is VERIFIED_INVALID CERTIFICATE_NOT_ENROLLED. Only VERIFIED_VALID stores the signed PDF as the "
   "document's next version, by a compare-and-set on the prepared version (decision 228): when the document changed "
   "meanwhile nothing is stored, the outcome is recorded as INDETERMINATE and the answer is 409 DOCUMENT_CHANGED "
   "(prepare and sign again). Completing the task is a separate call." + _DSC_COMMON,
   _DSC_REQ, params=[ID], body="DscCompleteRequest", resp=(200, "DscCompleteResponse"), cmd="dsc.complete",
   perm="task.write", errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound", "NotImplemented"))

_DSC_400 = {"description": "VALIDATION_FAILED: a required field is missing (prepare: documentId and certificateChain; "
                           "complete: prepareId, signature, algorithm and certificateChain), or an Idempotency-Key "
                           "problem as for every command", "content": PROBLEM}
_DSC_403 = {"description": "ACTION_NOT_PERMITTED (task.write, and document.read on prepare or document.write on "
                           "complete), NOT_ASSIGNEE: only the task's assignee can sign it, or POLICY_DENIED as for "
                           "every command", "content": PROBLEM}
_DSC_404 = {"description": "The task or the document is not found or not visible to the caller", "content": PROBLEM}
_DSC_501 = {"description": "CAPABILITY_NOT_INSTALLED: DSC signing is off (sangam.dsc.enabled, default false) or the "
                           "optional module sangam-esign is not installed. Not retryable", "content": PROBLEM}
_DSC_503 = {"description": "Temporary: BACKPRESSURE, AUDIT_UNAVAILABLE or POLICY_UNAVAILABLE as for every command",
            "headers": {"Retry-After": H("Retry-After")}, "content": PROBLEM}
_DSC_ERR = {
    "prepareDscSignature": {
        "409": "TASK_SUSPENDED: the task is suspended; DOCUMENT_NOT_CLEAN: the document's malware scan is INFECTED or "
               "PENDING; DOCUMENT_CHANGED: the content does not match its recorded SHA-256; or an Idempotency-Key "
               "conflict as for every command",
        "422": "CERTIFICATE_NOT_ENROLLED: the signer certificate is not the caller's approved enrolment (decision "
               "227); CERTIFICATE_CHAIN_INVALID: 1 to 10 certificates needed, or one cannot be "
               "read; INVALID_INPUT: a certificate is not Base64 or too large; DSC_ALGORITHM: the signer key is "
               "neither RSA nor EC; DOCUMENT_EMPTY; DOCUMENT_NOT_OF_TASK: not attached to the task or its process "
               "instance; DOCUMENT_NOT_PDF; DOCUMENT_NO_CHECKSUM; DOCUMENT_TOO_LARGE: over 50 MiB; PDF_INVALID: the "
               "PDF cannot be prepared; ALREADY_SIGNED: the prepared signature field is not empty; or a business "
               "rule as for every command",
        "503": "DOCUMENT_UNREADABLE: the document content cannot be read (Retry-After 5); or, as for every command, "
               "BACKPRESSURE, AUDIT_UNAVAILABLE or POLICY_UNAVAILABLE",
    },
    "completeDscSignature": {
        "409": "DSC_PREPARE_NOT_USABLE: the prepare is unknown, expired, already used, or belongs to another tenant, "
               "task or principal; TASK_SUSPENDED; DOCUMENT_CHANGED: the document has a newer version than the one "
               "that was signed, found before the write or by the compare-and-set (decision 228); the signature was "
               "not stored, prepare and sign again; or an Idempotency-Key conflict as for every command",
        "422": "A business rule as for every command (verification failures are a 200 outcome, not a 422)",
        "503": None,
    },
}
for _p, _oid in (("/tasks/{id}/dsc/prepare", "prepareDscSignature"),
                 ("/tasks/{id}/dsc/complete", "completeDscSignature")):
    _r = PATHS[_p]["post"]["responses"]
    _r["400"] = _DSC_400
    _r["403"] = _DSC_403
    _r["404"] = _DSC_404
    _r["501"] = _DSC_501
    _r["409"] = {"description": _DSC_ERR[_oid]["409"], "content": PROBLEM}
    _r["422"] = {"description": _DSC_ERR[_oid]["422"], "content": PROBLEM}
    _r["503"] = (_DSC_503 if _DSC_ERR[_oid]["503"] is None else
                 {"description": _DSC_ERR[_oid]["503"], "headers": {"Retry-After": H("Retry-After")},
                  "content": PROBLEM})

# ---------------------------------------------------------------- decision 227: certificate enrolment
# As coded in sangam-server DscCertificateController and sangam-integration DscEnrolment. A signer enrols the
# certificate of their token (task.write); an admin other than the signer approves or rejects it
# (dsc.certificate.approve, W4-B CHANGE 2026-11-13T02:19:08Z: tenant-scoped, persona sangam-tenant-admin); the signer or
# an admin revokes it. Every change and its audit record (DSC_CERTIFICATE_ENROLLED, _APPROVED, _REJECTED, _REVOKED,
# chain dsc-certificate:<certificateSha256>) commit in one transaction.

TAGS.append(("DscCertificates", "DSC certificate enrolment and admin approval (decision 227)"))
_DCE_TAG = "DscCertificates"
_DCE_ADMIN = "dsc.certificate.approve"
_DCE_STATUS = ["PENDING", "APPROVED", "REJECTED", "REVOKED"]
_DCE_OFF = (" Without the opt-in wiring (sangam.dsc.enabled, which needs the optional module sangam-esign) the call "
            "answers 501 CAPABILITY_NOT_INSTALLED.")

SCHEMAS["DscCertificate"] = {
    "type": "object",
    "description": "One enrolment of a DSC certificate. Fingerprint, issuer, serial, subject and validity are derived "
                   "by the server from the DER; the subject is shown, never trusted.",
    "required": ["enrolmentId", "principalId", "certificateSha256", "issuer", "serialHex", "subject", "notBefore",
                 "notAfter", "status", "enrolledAt"],
    "properties": {
        "enrolmentId": {"type": "string"},
        "principalId": {"type": "string", "description": "The signer who enrolled it"},
        "certificateSha256": {"type": "string", "pattern": "^[0-9a-f]{64}$",
                              "description": "SHA-256 (lower-case hex) of the certificate DER"},
        "issuer": {"type": "string", "description": "Issuer distinguished name (RFC 2253)"},
        "serialHex": {"type": "string", "description": "Serial number, hexadecimal"},
        "subject": {"type": "string", "description": "Subject distinguished name (RFC 2253), for display only"},
        "notBefore": {"type": "string", "format": "date-time"},
        "notAfter": {"type": "string", "format": "date-time"},
        "status": {"type": "string", "enum": _DCE_STATUS,
                   "description": "PENDING until an admin decides; only APPROVED binds a signer at prepare and "
                                  "complete. A REJECTED or REVOKED certificate can be enrolled again under a new "
                                  "enrolment id"},
        "enrolledAt": {"type": "string", "format": "date-time"},
        "decidedBy": {"type": ["string", "null"], "description": "Who approved, rejected or revoked it"},
        "decidedAt": {"type": ["string", "null"], "format": "date-time"},
        "decisionReason": {"type": ["string", "null"]},
    },
}
SCHEMAS["DscCertificateEnrolmentRequest"] = {
    "type": "object", "description": "Enrol the certificate of the caller's token.", "required": ["certificate"],
    "properties": {"certificate": {"type": "string", "minLength": 1,
                                   "description": _DSC_B64 + " DER X.509 certificate of the signer, at most 16 KiB "
                                                             "decoded"}},
}
SCHEMAS["DscCertificateDecision"] = {
    "type": "object",
    "description": "Reason for an approval (optional) or a rejection or revocation (required, 422 REASON_REQUIRED).",
    "properties": {"reason": {"type": "string", "maxLength": 512,
                              "description": "Trimmed; longer than 512 characters is 422 REASON_TOO_LONG"}},
}

op("post", "/me/dsc/certificates", "enrolDscCertificate", _DCE_TAG, "Enrol a DSC certificate",
   "The signer enrols the certificate of their token; the enrolment is PENDING until an admin other than the signer "
   "approves it. Needs task.write. Audited DSC_CERTIFICATE_ENROLLED." + _DCE_OFF,
   _DSC_REQ, body="DscCertificateEnrolmentRequest", resp=(201, "DscCertificate"), created=True,
   cmd="dsc.certificate.enrol", perm="task.write",
   errors=("BadRequest", "Unauthorized", "Forbidden", "NotImplemented"))
op("get", "/me/dsc/certificates", "listMyDscCertificates", _DCE_TAG, "My DSC certificates",
   "The caller's enrolments in every status. Needs task.write." + _DCE_OFF,
   _DSC_REQ, resp=(200, "array:DscCertificate"), perm="task.write",
   errors=("Unauthorized", "Forbidden", "NotImplemented"))
op("post", "/me/dsc/certificates/{enrolmentId}/revoke", "revokeMyDscCertificate", _DCE_TAG,
   "Revoke my DSC certificate",
   "The signer revokes their own PENDING or APPROVED enrolment without approval (revocation removes trust); a reason "
   "is required. Another user's enrolment answers 404 ENROLMENT_NOT_FOUND, as an unknown id does. Needs task.write. "
   "Audited DSC_CERTIFICATE_REVOKED." + _DCE_OFF,
   _DSC_REQ, params=[pp("enrolmentId")], body="DscCertificateDecision", resp=(200, "DscCertificate"),
   cmd="dsc.certificate.revoke-own", perm="task.write",
   errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound", "NotImplemented"))
op("get", "/admin/dsc/certificates", "listDscCertificates", _DCE_TAG, "DSC certificate enrolments",
   "Enrolments of the tenant in one status, PENDING by default (the approval queue). Needs " + _DCE_ADMIN + "."
   + _DCE_OFF, _DSC_REQ,
   params=[q("status", desc="PENDING (default), APPROVED, REJECTED or REVOKED; another value is 400 "
                            "VALIDATION_FAILED", enum=_DCE_STATUS),
           q("limit", "int", "At most this many rows, 1 to 200 (default 200; values outside are clamped)")],
   resp=(200, "array:DscCertificate"), perm=_DCE_ADMIN,
   errors=("BadRequest", "Unauthorized", "Forbidden", "NotImplemented"))
op("get", "/admin/dsc/certificates/{enrolmentId}", "getDscCertificate", _DCE_TAG, "A DSC certificate enrolment",
   "One enrolment of the tenant. Needs " + _DCE_ADMIN + "." + _DCE_OFF, _DSC_REQ, params=[pp("enrolmentId")],
   resp=(200, "DscCertificate"), perm=_DCE_ADMIN, errors=("Unauthorized", "Forbidden", "NotFound", "NotImplemented"))
for _verb, _oid, _sum, _cmd, _txt in (
        ("approve", "approveDscCertificate", "Approve a DSC certificate", "dsc.certificate.approve",
         "Approves a PENDING enrolment of another user; the reason is optional. An expired certificate is 422 "
         "CERTIFICATE_EXPIRED. Audited DSC_CERTIFICATE_APPROVED."),
        ("reject", "rejectDscCertificate", "Reject a DSC certificate", "dsc.certificate.reject",
         "Rejects a PENDING enrolment of another user; a reason is required. Audited DSC_CERTIFICATE_REJECTED."),
        ("revoke", "revokeDscCertificate", "Revoke a DSC certificate", "dsc.certificate.revoke",
         "Revokes a PENDING or APPROVED enrolment of any user of the tenant; a reason is required. Audited "
         "DSC_CERTIFICATE_REVOKED.")):
    op("post", "/admin/dsc/certificates/{enrolmentId}/" + _verb, _oid, _DCE_TAG, _sum,
       _txt + " Needs " + _DCE_ADMIN + "; an admin never decides their own enrolment (403 SELF_APPROVAL on approve "
       "and reject)." + _DCE_OFF, _DSC_REQ, params=[pp("enrolmentId")], body="DscCertificateDecision",
       resp=(200, "DscCertificate"), cmd=_cmd, perm=_DCE_ADMIN,
       errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound", "NotImplemented"))

_DCE_501 = {"description": "CAPABILITY_NOT_INSTALLED: DSC signing is off (sangam.dsc.enabled, default false) or the "
                           "optional module sangam-esign is not installed. Not retryable", "content": PROBLEM}
_DCE_404 = {"description": "ENROLMENT_NOT_FOUND: no such enrolment in the tenant (on the /me route also another "
                           "user's enrolment)", "content": PROBLEM}
_DCE_422_REASON = "REASON_REQUIRED: a reason is required; REASON_TOO_LONG: longer than 512 characters"
_DCE_ERR = {
    "enrolDscCertificate": {
        "400": "VALIDATION_FAILED: certificate is required; or an Idempotency-Key problem as for every command",
        "403": "ACTION_NOT_PERMITTED (task.write), or POLICY_DENIED as for every command",
        "409": "CERTIFICATE_ALREADY_ENROLLED: the certificate is already enrolled (PENDING or APPROVED) in the "
               "tenant; or an Idempotency-Key conflict as for every command",
        "422": "CERTIFICATE_INVALID: the certificate cannot be read (not Base64 DER, too large); "
               "CERTIFICATE_KEY_UNSUPPORTED: the key is neither RSA nor EC; CERTIFICATE_EXPIRED"},
    "revokeMyDscCertificate": {
        "403": "ACTION_NOT_PERMITTED (task.write), or POLICY_DENIED as for every command",
        "409": "ENROLMENT_NOT_LIVE: the enrolment is already REJECTED or REVOKED, or was decided concurrently; or an "
               "Idempotency-Key conflict as for every command",
        "422": _DCE_422_REASON},
    "approveDscCertificate": {
        "403": "ACTION_NOT_PERMITTED (" + _DCE_ADMIN + "), SELF_APPROVAL: the enrolment is the caller's own, or "
               "POLICY_DENIED as for every command",
        "409": "ENROLMENT_NOT_PENDING: only a PENDING enrolment can be approved (also when decided concurrently); or "
               "an Idempotency-Key conflict as for every command",
        "422": "CERTIFICATE_EXPIRED; REASON_TOO_LONG: longer than 512 characters"},
    "rejectDscCertificate": {
        "403": "ACTION_NOT_PERMITTED (" + _DCE_ADMIN + "), SELF_APPROVAL: the enrolment is the caller's own, or "
               "POLICY_DENIED as for every command",
        "409": "ENROLMENT_NOT_PENDING: only a PENDING enrolment can be rejected (also when decided concurrently); or "
               "an Idempotency-Key conflict as for every command",
        "422": _DCE_422_REASON},
    "revokeDscCertificate": {
        "403": "ACTION_NOT_PERMITTED (" + _DCE_ADMIN + "), or POLICY_DENIED as for every command",
        "409": "ENROLMENT_NOT_LIVE: the enrolment is already REJECTED or REVOKED, or was decided concurrently; or an "
               "Idempotency-Key conflict as for every command",
        "422": _DCE_422_REASON},
}
for _p, _item in PATHS.items():
    if _p.startswith("/me/dsc/certificates") or _p.startswith("/admin/dsc/certificates"):
        for _m, _o in _item.items():
            _r = _o["responses"]
            _r["501"] = _DCE_501
            if "404" in _r:
                _r["404"] = _DCE_404
            for _code, _text in _DCE_ERR.get(_o["operationId"], {}).items():
                _r[_code] = {"description": _text, "content": PROBLEM}
PATHS["/admin/dsc/certificates"]["get"]["responses"]["400"] = {
    "description": "VALIDATION_FAILED: status is not PENDING, APPROVED, REJECTED or REVOKED", "content": PROBLEM}