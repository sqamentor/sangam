# SPDX-License-Identifier: Apache-2.0
# Owner decisions 167 and 153 (W4-D REQUEST 2026-11-12T19:49:29Z): the OCR sidecar runs as its own container
# (sangam-ocr-sidecar: org.sangam.ocrsidecar.OcrService on the shared org.sangam.integration.sidecar.SidecarServer), and
# the server calls it through org.sangam.integration.document.ocr.RemoteOcrClient. Both sides share the constants of
# RemoteOcrClient (RECOGNISE_PATH, LANGUAGES_PARAM, TEXT, ENGINE, LANGUAGES, LANGUAGE). This is the wire between them:
# an internal contract, not part of sangam-api.yaml and not an SDK input. The lang parameter is given once as a
# comma-separated list (OcrService.languages refuses a repeated lang with 400).
_OCR_LANG = "[a-z]{3}(_[a-z]{2,8})?"  # RemoteOcrClient.LANGUAGE
_OCR_IMAGES = ("image/png", "image/jpeg", "image/tiff")  # ImageCheck
_OCR_ERROR = {"application/json": {"schema": R("OcrError")}}
app("sangam-ocr-api.yaml", "Sangam OCR sidecar",
    "Internal OCR service for scanned documents (owner decisions 167 and 153)",
    "One stateless JDK HTTP server in its own container (Tesseract); it never stores or logs the image or the text. "
    "Only authorised callers reach its private port (the Sangam server: sangam.ocr.url / SANGAM_OCR_URL), over HTTPS "
    "directly or through an authenticated mesh. Every recognition request is authenticated with a bearer token before "
    "the image is read (decision 153); the token only authenticates the caller, user permissions and tenant ownership "
    "stay in the caller. The client fails closed: 400, 413, 415 and 422 are a refusal of the image (OcrRejected); no "
    "URL, no token, a timeout, 401, 500, 503, any other status or an answer of another shape is OcrUnavailable. Every "
    "error answer is `application/json` `{\"error\": ...}` and never contains a token; another method on a known "
    "path is 405, an unknown path 404.",
    {},
    [("Recognition", "Text recognition of one image"), ("Health", "Container probe")],
    servers=[{"url": "/", "description": "The OCR container (SANGAM_OCR_PORT, default 8086), HTTPS"}],
    problem=False,
    bearer=("OCR token (decision 153): a strong random value injected through secret configuration, never hard-coded, "
            "committed, sent to a browser or placed in a URL, log or error. The sidecar accepts each configured token "
            "(two during a rotation) and stops accepting a token as soon as it is removed; with no token configured it "
            "is not ready and refuses every recognition (503). Tokens are read from a token file (secret mount, "
            "SANGAM_OCR_TOKEN_FILE), never from the command line."))
SCHEMAS["OcrError"] = {"type": "object", "description": "Error answer of the OCR sidecar.", "required": ["error"],
                       "properties": {"error": {"type": "string"}}}
SCHEMAS["OcrResult"] = {
    "type": "object", "description": "The recognised text.", "required": ["text", "engine", "languages"],
    "properties": {
        "text": {"type": "string", "description": "Recognised text (at most 4 MiB as UTF-8)"},
        "engine": {"type": "string", "description": "Name and version of the engine, for example tesseract 5.3.4"},
        "languages": {"type": "array", "items": {"type": "string", "pattern": "^" + _OCR_LANG + "$"},
                      "description": "The languages used, exactly as asked and in the same order"},
    },
}
SCHEMAS["OcrHealth"] = {"type": "object", "description": "Probe answer: the status only.", "required": ["status"],
                        "properties": {"status": {"type": "string", "enum": ["UP", "NOT_READY"],
                                                  "description": "NOT_READY while no token is configured"}}}
aop("post", "/v1/ocr", "recogniseImage", "Recognition", "Recognise the text of an image",
    "Recognises the text of one PNG, JPEG or TIFF image in the requested languages. The bearer token is checked "
    "first: missing or wrong is 401 (WWW-Authenticate: Bearer) and the body is not read. The body limit is "
    "SANGAM_OCR_MAX_BYTES (default 33554432 bytes, 32 MiB) and the pixel limit SANGAM_OCR_MAX_PIXELS (default "
    "100000000); processing time and concurrent recognitions are limited too.", [],
    params=[{"name": "lang", "in": "query", "required": True,
             "description": "Comma-separated language codes (Tesseract traineddata names), for example eng,hin: given "
                            "once, each well-formed, distinct and offered by the sidecar (SANGAM_OCR_LANGUAGES, default "
                            "eng,hin)",
             "schema": {"type": "string", "pattern": "^" + _OCR_LANG + "(," + _OCR_LANG + ")*$"}}],
    body={"type": "string", "format": "binary"}, body_ct=_OCR_IMAGES,
    resp=(200, "OcrResult"), errors=(),
    more={"400": {"description": "lang missing, given more than once, malformed, repeated or not offered",
                  "content": _OCR_ERROR},
          "401": {"description": "No bearer token, or a token the sidecar does not accept; nothing was read",
                  "headers": {"WWW-Authenticate": {"schema": {"type": "string"}, "description": "Bearer"}},
                  "content": _OCR_ERROR},
          "413": {"description": "The body is larger than the byte limit, or the image has more pixels than the pixel "
                                 "limit", "content": _OCR_ERROR},
          "415": {"description": "The body is not a PNG, JPEG or TIFF image, or not the declared image type",
                  "content": _OCR_ERROR},
          "422": {"description": "The image size cannot be read, or the engine cannot read the image",
                  "content": _OCR_ERROR},
          "500": {"description": "The engine failed or gave no text", "content": _OCR_ERROR},
          "503": {"description": "Not ready (no token configured), busy (concurrency limit, with Retry-After), past the "
                                 "processing-time limit, or stopping; nothing was recognised",
                  "headers": {"Retry-After": {"schema": {"type": "string"}, "description": "Seconds; sent when busy"}},
                  "content": _OCR_ERROR}})
aop("get", "/health", "ocrHealth", "Health", "Health probe",
    "For container probes; no token, and the answer carries no secret. 200 UP when a token is configured, otherwise "
    "503 NOT_READY.", [], resp=(200, "OcrHealth"), errors=(),
    more={"503": {"description": "Not ready: no token configured",
                  "content": {"application/json": {"schema": R("OcrHealth")}}}})
PATHS["/health"]["get"]["security"] = []
