import os, re
R = r"D:\GITHUB  Project\Custom WorkFlow"
W = os.path.join(R, ".github", "workflows")
D = os.path.join(R, "sangam", "deploy")

def edit(path, pairs):
    s = open(path, encoding="utf-8").read()
    for old, new in pairs:
        assert old in s, (path, old[:70])
        s = s.replace(old, new)
    open(path, "w", encoding="utf-8", newline="\n").write(s)

# ---------- ci-build: JDK matrix
edit(os.path.join(W, "ci-build.yml"), [
    ("""    name: Maven build + unit tests (JDK 21)
    runs-on: ubuntu-24.04
    timeout-minutes: 45
    steps:""", """    name: Maven build + unit tests (JDK ${{ matrix.java }})
    runs-on: ubuntu-24.04
    timeout-minutes: 45
    strategy:
      fail-fast: false
      matrix:
        java: ["25", "21"]   # R5: 25 LTS is the baseline, 21 the supported minimum (deploy/SUPPORT-MATRIX.md)
    steps:"""),
    ('java-version: "21"', 'java-version: ${{ matrix.java }}'),
    ("name: unit-test-reports\n", "name: unit-test-reports-jdk${{ matrix.java }}\n"),
    ("""      - name: Stage server jar and MariaDB driver (LIC-5 separate jar)
        run: |""", """      - name: Stage server jar and MariaDB driver (LIC-5 separate jar)
        if: matrix.java == '25'
        run: |"""),
    ("""      - uses: actions/upload-artifact@v4
        with:
          name: server-jar""", """      - uses: actions/upload-artifact@v4
        if: matrix.java == '25'
        with:
          name: server-jar"""),
])

# ---------- JDK 25 elsewhere
edit(os.path.join(W, "db-matrix.yml"), [('java-version: "21"', 'java-version: "25"')])
for f in ("licence-check.yml", "security.yml"):
    edit(os.path.join(W, f), [('java-version: "21"', 'java-version: "25"')])

# ---------- sdks: native job + metadata drift
edit(os.path.join(W, "sdks.yml"), [("""  dotnet:
    runs-on: ubuntu-24.04""", """  java-native:
    # R7: native-light worker (GraalVM CE, no GraalJS). Offline smoke on the executable is the build evidence.
    name: Java SDK native-light worker
    runs-on: ubuntu-24.04
    timeout-minutes: 40
    steps:
      - uses: actions/checkout@v4
      - uses: graalvm/setup-graalvm@v1
        with:
          java-version: "25"
          distribution: graalvm-community
          cache: maven
          github-token: ${{ secrets.GITHUB_TOKEN }}
      - name: Native metadata matches the SDK model
        run: python3 -I sangam/deploy/native/sdk-worker/generate_native_config.py --check
      - name: Build native executable
        working-directory: sangam
        run: |
          mvn -B -ntp -DskipTests -pl sdks/java -am install
          mvn -B -ntp -DskipTests -f deploy/native/sdk-worker/pom.xml -Pnative package
      - name: Smoke
        working-directory: sangam/deploy/native/sdk-worker/target
        run: |
          ./sangam-sdk-worker --smoke | tee native-smoke.txt
          ls -l sangam-sdk-worker >> native-smoke.txt
      - uses: actions/upload-artifact@v4
        with:
          name: native-worker-evidence
          path: sangam/deploy/native/sdk-worker/target/native-smoke.txt
      - name: Image build (no push)
        working-directory: sangam
        run: docker buildx build -f deploy/docker/sdk-worker-native.Dockerfile --load -t sangam-sdk-worker:ci .

  dotnet:
    runs-on: ubuntu-24.04""")])

# ---------- release
edit(os.path.join(W, "release.yml"), [
    ('with: { distribution: temurin, java-version: "21", cache: maven', 'with: { distribution: temurin, java-version: "25", cache: maven'),
    ("""      - name: Stage artefacts
        run: |""", """      - name: Unit test reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: release-unit-test-reports
          path: sangam/**/target/surefire-reports/
          if-no-files-found: ignore
      - name: Stage artefacts
        run: |"""),
    ("""          cp sangam-server/target/sangam-server-$V.jar ../dist/sangam-server-$V.jar
""", """          cp sangam-server/target/sangam-server-$V.jar ../dist/sangam-server-$V.jar
          for app in sangam-control-plane sangam-gateway sangam-rollup; do
            cp $app/target/$app-$V.jar ../dist/$app-$V.jar
          done
"""),
    ("""      - name: sangam-ui
        if: steps.stage.outputs.ui == 'true'""", """      - name: SCALE images (control plane, gateway, roll-up)
        if: needs.meta.outputs.push == 'true'
        run: |
          V=${{ needs.meta.outputs.version }}
          for app in sangam-control-plane sangam-gateway sangam-rollup; do
            mkdir -p $app/target && cp ../dist/$app-$V.jar $app/target/$app.jar
            docker buildx build -f deploy/docker/server.Dockerfile --target runtime \\
              --build-arg APP_MODULE=$app --build-arg JAR_SOURCE=prebuilt --build-arg PREBUILT_JAR=$app/target/$app.jar \\
              --platform linux/amd64,linux/arm64 --provenance=mode=max --sbom=true --push \\
              -t "$REGISTRY/$app:$V" .
          done
      - name: Native-light worker image (R7)
        uses: docker/build-push-action@v6
        with:
          context: sangam
          file: sangam/deploy/docker/sdk-worker-native.Dockerfile
          platforms: linux/amd64
          push: ${{ needs.meta.outputs.push == 'true' }}
          tags: ${{ env.REGISTRY }}/sangam-sdk-worker:${{ needs.meta.outputs.version }}
      - name: sangam-ui
        if: steps.stage.outputs.ui == 'true'"""),
    ("""for img in sangam-server:$V sangam-server:$V-distroless $(""", """for img in sangam-server:$V sangam-server:$V-distroless sangam-control-plane:$V sangam-gateway:$V sangam-rollup:$V sangam-sdk-worker:$V $("""),
    ("""  publish:
    needs: [meta, build, images]""", """  evidence:
    # R52: audit evidence pack for a CERT-In-empanelled audit (GOV-3). A missing mandatory area fails the release.
    name: Audit evidence pack
    needs: [meta, build, db-matrix, security, images]
    runs-on: ubuntu-24.04
    timeout-minutes: 30
    permissions:
      contents: read
      actions: read
      security-events: read
      id-token: write   # keyless signature of SHA256SUMS
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "25" }
      - uses: actions/download-artifact@v4
        with: { name: release-dist, path: . }
      - uses: actions/download-artifact@v4
        if: needs.meta.outputs.push == 'true'
        with: { name: image-sbom, path: dist/sbom }
      - name: Collect pipeline reports
        uses: actions/download-artifact@v4
        with:
          path: pipeline
          pattern: "{db-*-reports,release-unit-test-reports,dependency-check-report}"
      - name: sangam-compliance evidence report
        run: bash sangam/deploy/evidence/generate_compliance_report.sh "dist/sangam-server-${{ needs.meta.outputs.version }}.jar" evidence-input/compliance
      - name: CodeQL summary
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          mkdir -p evidence-input/codeql
          gh api --paginate "repos/$GITHUB_REPOSITORY/code-scanning/alerts?ref=$GITHUB_REF&state=open&per_page=100" \\
            > evidence-input/codeql/open-alerts.json
          python3 -I -c "import json,collections as c;a=json.load(open('evidence-input/codeql/open-alerts.json'));json.dump({'ref':'$GITHUB_REF','openAlerts':len(a),'bySeverity':c.Counter((x.get('rule') or {}).get('security_severity_level') or (x.get('rule') or {}).get('severity') or 'unknown' for x in a)},open('evidence-input/codeql/summary.json','w'),indent=2)"
      - name: Configuration and version manifest
        run: |
          mkdir -p evidence-input/config
          cp sangam/deploy/helm/sangam/values.yaml sangam/deploy/helm/sangam/Chart.yaml sangam/deploy/SUPPORT-MATRIX.md \\
             sangam/deploy/airgap/images.txt sangam/pom.xml evidence-input/config/
          cp sangam/sangam-compliance/src/main/resources/compliance/jurisdictions/IN/obligations.tsv evidence-input/compliance/
      - name: Assemble pack
        run: |
          mkdir -p evidence-input/tests
          cp -r pipeline/db-*-reports pipeline/release-unit-test-reports evidence-input/tests/
          args=(--version "${{ needs.meta.outputs.version }}" --commit "$GITHUB_SHA" --out evidence --pom sangam/pom.xml
                --area sbom=dist/sbom --area licence=dist/sbom/licence-report.md
                --area dependency-check=pipeline/dependency-check-report --area codeql=evidence-input/codeql
                --area tests=evidence-input/tests --area compliance=evidence-input/compliance
                --area config=evidence-input/config
                --image "sangam-server@${{ needs.images.outputs.server-digest }}")
          [ "${{ needs.meta.outputs.push }}" = true ] || args+=(--allow-missing)
          python3 -I sangam/deploy/evidence/build_evidence_pack.py "${args[@]}"
      - uses: sigstore/cosign-installer@v3
        if: needs.meta.outputs.push == 'true'
      - name: Sign checksums (keyless)
        if: needs.meta.outputs.push == 'true'
        run: |
          V=${{ needs.meta.outputs.version }}
          cd evidence/sangam-evidence-$V
          cosign sign-blob --yes --bundle SHA256SUMS.sigstore.json SHA256SUMS
          cd .. && rm -f sangam-evidence-$V.tar.gz && tar -czf sangam-evidence-$V.tar.gz sangam-evidence-$V
      - uses: actions/upload-artifact@v4
        with:
          name: evidence-pack
          path: evidence/*.tar.gz
          retention-days: 90

  publish:
    needs: [meta, build, images, evidence]"""),
    ("""      - uses: azure/setup-helm@v4
        with: { version: v3.18.4 }
      - name: Helm chart""", """      - uses: actions/download-artifact@v4
        with: { name: evidence-pack, path: dist }
      - uses: azure/setup-helm@v4
        with: { version: v3.18.4 }
      - name: Helm chart"""),
    ("""            dist/SHA256SUMS
""", """            dist/SHA256SUMS
            dist/sangam-evidence-*.tar.gz
"""),
    ("""            SBOMs (CycloneDX 1.6) and""", """            Audit evidence pack (R52): `sangam-evidence-*.tar.gz`, start at `INDEX.md`; `SHA256SUMS` is signed with cosign (keyless).
            SBOMs (CycloneDX 1.6) and"""),
])

# ---------- air-gap list
edit(os.path.join(D, "airgap", "images.txt"), [("""product        ${SANGAM_REGISTRY}/sangam-simulators:${SANGAM_VERSION}
""", """product        ${SANGAM_REGISTRY}/sangam-control-plane:${SANGAM_VERSION}
product        ${SANGAM_REGISTRY}/sangam-gateway:${SANGAM_VERSION}
product        ${SANGAM_REGISTRY}/sangam-rollup:${SANGAM_VERSION}
product        ${SANGAM_REGISTRY}/sangam-sdk-worker:${SANGAM_VERSION}
product        ${SANGAM_REGISTRY}/sangam-simulators:${SANGAM_VERSION}
""")])

# ---------- README
edit(os.path.join(D, "README.md"), [
    ("""---

## 0. Layout
""", """Also: `SUPPORT-MATRIX.md` (R5, R7: JDK, framework, driver and deployment matrix, native-light APIs) and `evidence/` (R52: audit evidence pack).

**Runtime since this update:**

- JDK 25 is the default image runtime.
- Spring Boot is 4.1.2.
- Environment names follow `sangam-server/src/main/resources/application*.yaml` (`SANGAM_DB_URL`, `SANGAM_OIDC_ISSUER`, `SANGAM_OTLP_ENABLED`, ...).
- `server.Dockerfile` also builds the SCALE apps through `--build-arg APP_MODULE=`.
- Writable state is `/var/lib/sangam`. It holds the compliance log archive, so back it up and keep it at least 180 days (R51). Helm: `server.state`, ReadWriteMany.
- SCALE on compose: `docker compose -f docker-compose.yml -f docker-compose.scale.yml --profile postgres up -d`. On Helm: `scale.*` (`examples/values-scale.yaml`).

---

## 0. Layout
"""),
    ("- Packaging: a Spring Boot 3.x fat jar", "- Packaging: a Spring Boot 4.1.2 fat jar"),
    ("| `release.yml` | tag `vX.Y.Z` | full Tier-1 matrix + security as gates →", "| `release.yml` | tag `vX.Y.Z` | also builds the R52 evidence pack (`evidence/`: SBOMs, licence, dependency-check, CodeQL summary, test reports, compliance report, config manifest, cosign-signed checksums; a missing mandatory area fails the release); full Tier-1 matrix + security as gates →"),
])
open(os.path.join(D, "helm", "sangam", "examples", "values-scale.yaml"), "w", encoding="utf-8", newline="\n").write("""# SCALE: server as a cell, plus control plane, gateway and national roll-up. Layer on a database example:
#   helm template sangam deploy/helm/sangam -f examples/values-postgres.yaml -f examples/values-scale.yaml
# Secrets (keys in values.yaml, scale.*): sangam-cell, sangam-cp, sangam-gw, sangam-ru.
database:
  type: postgres
  host: pg-primary.db.svc.cluster.local
  existingSecret: sangam-db
oidc:
  issuerUri: https://sso.example.gov.in/realms/sangam
scale:
  cell:
    id: cell-in-rj-1
    region: in-rj
    publicUrl: https://cell-1.sangam.example.gov.in
    existingSecret: sangam-cell
  controlPlane: { enabled: true, existingSecret: sangam-cp }
  gateway: { enabled: true, existingSecret: sangam-gw, hostSuffix: .sangam.example.gov.in }
  rollup: { enabled: true, existingSecret: sangam-ru }
""")
print("ok")
