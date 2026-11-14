# SPDX-License-Identifier: Apache-2.0
# Owner decisions 114 and 119 (RM-26, SL-178): the PDF/A and PDF/UA validator runs as its own container
# (sangam-pdf-validator: org.sangam.pdfvalidator.PdfValidatorService), and the server calls it through
# org.sangam.integration.document.pdf.remote.RemotePdfValidator. Both sides share the constants of RemotePdfValidator
# (VALIDATE_PATH, CONFORMANCE_PARAM, PDF_CONTENT_TYPE, REPORT_CONTENT_TYPE). This is the wire between them: an internal
# contract, not part of sangam-api.yaml and not an SDK input (W4-D REQUEST 2026-11-12T16:56:21Z).
# Owner decision 153 (W4Q-10) supersedes "no authentication": every validation request carries a bearer token that is
# checked before the PDF is read, in addition to network isolation, over HTTPS (directly or an authenticated mesh).
# The authentication sits behind a replaceable interface on both sides (per-service identity or mTLS later).
_PV_CONFORMANCE = ["PDF_A_2B", "PDF_UA_1"]  # PdfRenderer.Conformance
_PV_ERROR = {"application/json": {"schema": R("ValidatorError")}}
app("sangam-pdf-validator-api.yaml", "Sangam PDF validator",
    "Internal PDF/A-2b and PDF/UA-1 validation service (owner decisions 114, 119 and 153)",
    "One stateless JDK HTTP server in its own container; it never stores or logs the PDF it is sent. Only authorised "
    "callers reach its private port (the Sangam server: sangam.pdf.validator.url / SANGAM_PDF_VALIDATOR_URL), over "
    "HTTPS directly or through an authenticated mesh. Every validation request is authenticated with a bearer token "
    "before the PDF is read (decision 153); the token only authenticates the caller, user permissions and tenant "
    "ownership stay in the caller. The client fails closed: no URL, no token, a timeout, a refused connection, any "
    "status other than 200 or a malformed or inconsistent report make PDF output answer 422 FORMAT_NOT_AVAILABLE in "
    "the server, never an unvalidated PDF. Every error answer is `application/json` `{\"error\": ...}` and never "
    "contains a token; another method on a known path is 405 with `Allow`, an unknown path 404.",
    {},
    [("Validation", "PDF/A and PDF/UA conformance of a rendered PDF"), ("Health", "Container probe")],
    servers=[{"url": "/", "description": "The validator container (SANGAM_PDF_VALIDATOR_PORT, default 8085), HTTPS"}],
    problem=False,
    bearer=("Validator token (decision 153): a strong random value injected through secret configuration, never "
            "hard-coded, committed, sent to a browser or placed in a URL, log or error. Distinct tokens per caller are "
            "preferred; a single shared token cannot tell callers apart and is given to the Sangam server only. The "
            "validator accepts each configured token (two during a rotation, the new one and the old one) and stops "
            "accepting a token as soon as it is removed: there is no permanent fallback token. With no token "
            "configured the validator is not ready and refuses every validation (503). A demo uses the same mechanism "
            "with its own synthetic token. Tokens are read from a token file (secret mount), never from the "
            "command line."))
SCHEMAS["ValidatorError"] = {"type": "object", "description": "Error answer of the validator.", "required": ["error"],
                             "properties": {"error": {"type": "string"}}}
SCHEMAS["ValidationReport"] = {
    "type": "object", "description": "The validation report; ok is true only when findings is empty. Findings are "
                                     "conformance results only; authentication and processing errors are never "
                                     "reported as findings.",
    "required": ["ok", "validator", "checked", "findings"],
    "properties": {
        "ok": {"type": "boolean"},
        "validator": {"type": "string", "description": "Name and version of the validator that checked the PDF"},
        "checked": {"type": "array", "items": {"type": "string", "enum": _PV_CONFORMANCE},
                    "description": "The conformance levels checked, sorted by name"},
        "findings": {"type": "array", "items": {"type": "string"}, "description": "Failed rules; empty when ok"},
    },
}
SCHEMAS["ValidatorHealth"] = {"type": "object", "description": "Probe answer: the status only (no validator name).", "required": ["status"],
                              "properties": {"status": {"type": "string", "enum": ["UP", "NOT_READY"],
                                                        "description": "NOT_READY while no token is configured"}}}
aop("post", "/v1/validate", "validatePdf", "Validation", "Validate a PDF",
    "Checks the PDF in the body against every requested conformance level. The bearer token is checked first: "
    "missing or wrong is 401 and the body is not read. The body limit is SANGAM_PDF_VALIDATOR_MAX_BYTES (default "
    "67108864 bytes, 64 MiB); a declared Content-Length over it is refused before the body is read. Processing time, "
    "concurrent validations and temporary storage are limited too.", [],
    params=[{"name": "conformance", "in": "query", "required": True, "style": "form", "explode": False,
             "description": "Comma-separated conformance levels, for example PDF_A_2B,PDF_UA_1; the parameter may be "
                            "given once",
             "schema": {"type": "array", "minItems": 1, "items": {"type": "string", "enum": _PV_CONFORMANCE}}}],
    body={"type": "string", "format": "binary"}, body_ct=("application/pdf",),
    resp=(200, "ValidationReport"), errors=(),
    more={"400": {"description": "conformance missing, given more than once, or naming an unknown level",
                  "content": _PV_ERROR},
          "401": {"description": "No bearer token, or a token the validator does not accept; nothing was read",
                  "headers": {"WWW-Authenticate": {"schema": {"type": "string"}, "description": "Bearer"}},
                  "content": _PV_ERROR},
          "413": {"description": "The PDF is larger than the limit", "content": _PV_ERROR},
          "500": {"description": "The validator failed or gave no report; the client refuses the PDF", "content": _PV_ERROR},
          "503": {"description": "Not ready (no token configured), busy (concurrency limit, with Retry-After), past the "
                                 "processing-time limit, or stopping; nothing was validated and the client refuses the "
                                 "PDF",
                  "headers": {"Retry-After": {"schema": {"type": "string"}, "description": "Seconds; sent when busy"}},
                  "content": _PV_ERROR}})
aop("get", "/health", "pdfValidatorHealth", "Health", "Health probe",
    "For container probes; no token, and the answer carries no secret. 200 UP when a token is configured, otherwise "
    "503 NOT_READY.", [], resp=(200, "ValidatorHealth"), errors=(),
    more={"503": {"description": "Not ready: no token configured",
                  "content": {"application/json": {"schema": R("ValidatorHealth")}}}})
PATHS["/health"]["get"]["security"] = []