import datetime, pathlib

HAND = pathlib.Path(r"D:\GITHUB  Project\sangam-handoff")
SP = pathlib.Path(r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad")

claims = HAND / "FILE-CLAIMS.md"
text = claims.read_text(encoding="utf-8")
lines = text.split("\n")
# remove the malformed line written by the failed shell run (starts with " B64 RELEASE", empty timestamp)
trail = text.endswith("\n")
body = [l for l in lines if not l.startswith(" B64 RELEASE W4-D Wave 4b")]
assert len(body) == len(lines) - 1, "malformed line not found exactly once"
text = "\n".join(body)

pairs = []
for row in (SP / "w4d-hashes.txt").read_text(encoding="utf-8-sig").splitlines():
    parts = row.split()
    if len(parts) >= 2 and parts[0] != "sangam/sangam-pdf-validator/pom.xml":
        pairs.append(f"{parts[0]} {parts[1]}")
HL = " ; ".join(pairs)
for p in pairs:
    assert "|" not in p

T = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

posts = [
    ("REQUEST", "W4-M (validator contract, decision 153): sangam-pdf-validator-api.yaml and PdfValidatorContractDriftTest need (1) a bearer securityScheme on POST /v1/validate (shared token; the server answers 401 with WWW-Authenticate Bearer before reading the body), (2) responses 401 and 503 (503 = not ready, busy, timed out or stopping; body {error}), (3) GET /health stays unauthenticated and answers only {status}: UP with 200 or NOT_READY with 503. The drift test's response list and its security == [] assertion must change. 401 and the 503 busy and timeout answers are sent by org.sangam.integration.sidecar.SidecarServer, not PdfValidatorService; the literal send(ex, 400 or 413 or 500, Map.of(error lines in PdfValidatorService are kept.", "W4-M"),
    ("REQUEST", "W4-M (new OCR contract, decision 167): please add api/apps/ocr.py giving sangam-ocr-api.yaml plus a drift test. POST /v1/ocr, query lang (repeatable, pattern [a-z]{3}(_[a-z]{2,8})?, allowlist from SANGAM_OCR_LANGUAGES), body image/png or image/jpeg or image/tiff, answer {text, engine, languages}; errors 400 401 413 415 422 500 503 with {error}; bearer security; GET /health as for the validator. Constants to read: RemoteOcrClient (RECOGNISE_PATH, LANGUAGES_PARAM, TEXT, ENGINE, LANGUAGES, LANGUAGE), sangam-ocr-sidecar OcrService and ImageCheck.", "W4-M"),
    ("REQUEST", "W4-C (deploy, decisions 153 157 167 184): (1) compose and helm for pdf-validator: token secret file on both sides (server SANGAM_PDF_VALIDATOR_TOKEN_FILE; client sangam.pdf.validator.token-file or the same env name), TLS keystore and password-file secrets (SANGAM_PDF_VALIDATOR_TLS_KEYSTORE, SANGAM_PDF_VALIDATOR_TLS_KEYSTORE_PASSWORD_FILE) or SANGAM_PDF_VALIDATOR_TRANSPORT=mesh behind a mesh, a trust store for the server-side client, NetworkPolicy ingress from the server only and no egress; demo credentials generated at start, never committed. (2) New ocr service, image and release job from deploy/docker/ocr.Dockerfile (required build arg UBUNTU_SNAPSHOT), same secret pattern with prefix SANGAM_OCR, port 8086, a distributions.json row, licence and SBOM handling of the OS packages (Tesseract, Leptonica, language data) under decision 118. (3) Decision 157: script-worker.Dockerfile now takes one required build arg UBUNTU_SNAPSHOT (YYYYMMDDTHHMMSSZ) instead of the three version args; please update script-worker-build-args.env, compose lines 368-372 and release.yml to pass and record it. (4) Decision 184: simulators.Dockerfile should EXPOSE 2525 for kind smtp and document SANGAM_SIM_SMTP_PORT, SANGAM_SIM_PG_CALLBACK_URL and the optional data-folder file dlt-templates.json; same in the deploy/README Integration notes. Jar: mvn -P simulators-app package -pl sangam-integration (classifier simulators).", "W4-C"),
    ("REQUEST", "integrator: (1) root pom module sangam-ocr-sidecar next to sangam-pdf-validator (it compiles with javac today, not yet through the reactor). (2) Register rows: 153 CODED, tests NOT_RUN; 157 CODED, apt snapshot syntax NOT_RUN until the first image build; 167 CODED (port, client, sidecar, image), tests NOT_RUN; M-103 PARTIAL (knowledge-base wiring of OCR text and the review screen remain); 184 CODED, packaging and image NOT_RUN.", "integrator"),
    ("INFO", "W4-H: the simulator launcher org.sangam.integration.sim.launcher uses the existing sim classes unchanged through their public constructors; DLT templates are read from the data-folder file dlt-templates.json when present. No W4-H file was edited; the only pom change is the simulators-app profile under the decision 184 grant.", "W4-H"),
    ("INFO", "Below 97 percent, NOT_RUN: (a) apt-get update --snapshot and apt-get install --snapshot with a YYYYMMDDTHHMMSSZ id is NOT_RUN until the first image build (decisions 157 and 167); (b) whether spring-boot-maven-plugin repackage puts provided and optional dependencies of sangam-integration into the simulators jar is NOT_RUN (the launcher needs compile-scope classes and jackson only; the image build will show any gap); (c) no licence claim is made for Tesseract, Leptonica or the language data; the NOTICE points to the per-package copyright files.", "integrator, W4-C"),
    ("DONE", "Wave 4b W4-D: 153 CODED, 157 CODED (syntax NOT_RUN), 167 CODED (M-103 PARTIAL), 184 CODED (packaging NOT_RUN). Tests written, NOT_RUN. Gate -pl sangam-integration,sangam-pdf-validator -am test-compile -Dsangam.target=target-w4d EXIT 0. sangam-ocr-sidecar (not yet a reactor module) javac --release 21 main and test EXIT 0. Gate including sangam-server EXIT 1 only in other lanes (sangam-rest BackupController and OpsRestConfiguration, W4-I decision 155 in progress); PdfValidatorServiceAbsentTest compiled alone with javac against the fresh integration classes and prebuilt module classes EXIT 0. sangam-pdf-validator/pom.xml claimed, not changed. Claims released in FILE-CLAIMS.md. SHA-256: " + HL, "W4-M, W4-C, integrator"),
]
board = HAND / "WAVE4-SYNC-BOARD.md"
b = board.read_text(encoding="utf-8")
add = "".join(f"{T} | W4-D/B64 | {k} | {t} | {g}\n" for k, t, g in posts)
for k, t, g in posts:
    assert "|" not in t
if not b.endswith("\n"):
    add = "\n" + add
with open(board, "a", encoding="utf-8", newline="") as f:
    f.write(add)

rel = f"{T} B64 RELEASE W4-D Wave 4b, every claim of 18:45:21Z (sangam/sangam-pdf-validator/pom.xml unchanged 33C4259B5FAB04950C2EBB2B77ECA8E82AB4EE49058A0ECD616B9B87FED90A47): {HL} . B64 holds no claims."
text = text.rstrip("\n") + "\n" + rel + "\n"
claims.write_text(text, encoding="utf-8", newline="")
print(T, len(pairs))
