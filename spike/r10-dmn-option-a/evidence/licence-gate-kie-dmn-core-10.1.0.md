# Sangam licence check (distribution server-image)

- SBOM D:/sgw/r10-dmn-scratch/deptree/target/bom.json: 59 components

## Violations (4)
- FORBIDDEN LICENCE com.github.javaparser:javaparser-core@3.25.8 [bom.json]: GNU Lesser General Public License AND Apache-2.0
- UNKNOWN LICENCE org.antlr:antlr-runtime@3.5.2 [bom.json]: BSD licence (add to the policy after review)
- UNKNOWN LICENCE org.antlr:antlr4-runtime@4.10.1 [bom.json]: BSD-4-Clause (add to the policy after review)
- VERSION com.fasterxml.jackson.core:jackson-databind@2.17.2 [bom.json] must be at least 2.22.3: ARCHITECTURE.md 1.7 Jackson >= 2.22.3 (spike E12-E15)

## Warnings (0)
- none

## Conditional, elected or excepted (1)
- CONDITIONAL io.github.x-stream:mxparser@1.2.2 [bom.json]: Indiana University Extreme! Lab Software License -> Owner decision 263 (2026-11-13, replaces decision 226: xpp3_min is out; W4-K re-resolve and swap in sangam-connectors-health, DOWNLOADS-WAVE4.md): Indiana University Extreme! Lab Software License, Version 1.2 (the jar META-INF/LICENSE), BSD style. Only io.github.x-stream:mxparser 1.2.2 as an optional dependency of the HAPI FHIR validation path. Where it ships, the distribution NOTICE carries the copyright, the licence and the IU acknowledgement from attributions/mxparser-1.2.2.txt (notice_bundle.py); no endorsement and no 'Indiana University' in a product name. Its xmlpull:xmlpull 1.1.3.1 dependency is public domain (LicenseRef-Public-Domain).

**Result: FAIL**
