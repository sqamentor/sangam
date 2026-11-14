import sys, datetime
ts = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
H = r"D:\GITHUB  Project\sangam-handoff"
hashes = ("sangam/deploy/docker/server.Dockerfile 06D38FA32CB270332BD257E1F4CC3E67EB7B66A57399404BE697A5C40D48CF5E, "
 "sangam/deploy/compose/docker-compose.yml 93304F51EC971CD3B447E002A078AA63E0D210334C65933AD2EEF6EAB9B8C76E, "
 "sangam/deploy/compose/docker-compose.keycloak-secret.yml (new) 64B933A4A4C15234B736CF01CF39EC15E759297EAF694292B21482A3AAD76A71, "
 "sangam/deploy/compose/.env.example 98EB54FDE1901F3CE2C7117B9D768C301A9C4CF2832DE9AB0A1EF81E8DDE878A, "
 "sangam/deploy/helm/sangam/values.yaml F15F603A573B82A4C68A0BDDDDB31110C71AE1D763B1B74DE0500AD7DBB5FCEA, "
 "sangam/deploy/helm/sangam/templates/server-configmap.yaml A634DABC6E0A4FDFFA7A59B51FA81A9E3B479FE6873414AD394E022B1EFEE25D")
board = (f"{ts} | W3-M/B54 | DONE | decision 113 + M-081 deploy (CHANGE 14:46:11Z): compose one-shot keycloak-broker-otp (keycloak:26.3.3, configure-broker-otp.sh mounted ro, depends_on keycloak service_started as keycloak has no healthcheck, restart on-failure, network sangam, dev fallback KEYCLOAK_ADMIN_PASSWORD like the keycloak service) + new override docker-compose.keycloak-secret.yml giving KEYCLOAK_ADMIN_PASSWORD_FILE from compose secret file KEYCLOAK_ADMIN_PASSWORD_SECRET (:? required, no literal secret); .env.example documents it; Helm has no Keycloak and no Keycloak Job pattern so values.yaml oidc carries an OPEN POINT comment (run the script against the external IdP). M-081: server.Dockerfile layout copies deploy/fonts/*.ttf + OFL-*.txt to /opt/sangam/fonts, SANGAM_PDF_FONTS set in both runtime stages, licence guard unchanged; compose server env and Helm server.pdfFonts -> server-configmap SANGAM_PDF_FONTS with comments. Lint: docker compose config (offline) EXIT 0 for base and base+override, missing secret path fails with the :? message; helm not installed so no helm lint/template (no download); PyYAML absent. Hashes: {hashes}. Open: bootstrap admin password (KC_BOOTSTRAP_ADMIN_PASSWORD) must equal the secret file on first start. No test runs, no pulls.")
claim = f"{ts} B54 RELEASE W3-M decision 113 + M-081 deploy (claim 14:46:11Z): {hashes}. B54 holds no claims."
for name, line in (("WAVE3-SYNC-BOARD.md", board), ("FILE-CLAIMS.md", claim)):
    assert "|" not in line.split(" | ", 3)[-1] if name.startswith("WAVE3") else "|" not in line
    p = H + "\\" + name
    data = open(p, "rb").read()
    eol = b"\r\n" if b"\r\n" in data else b"\n"
    if not data.endswith(eol): data += eol
    open(p, "ab").write((b"" if open(p,"rb").read().endswith(eol) else eol) + line.encode("utf-8") + eol)
print(ts)
