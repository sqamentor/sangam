// SPDX-License-Identifier: Apache-2.0
// Static check: values.yaml (and variants) against values.schema.json with ajv (strict off), no network.
const path = require('path');
const fs = require('fs');
const nm = process.argv[2];
const chart = process.argv[3];
const yaml = require(path.join(nm, 'js-yaml'));
const Ajv = require(path.join(nm, 'ajv'));
const schema = JSON.parse(fs.readFileSync(path.join(chart, 'values.schema.json'), 'utf8'));
const base = yaml.load(fs.readFileSync(path.join(chart, 'values.yaml'), 'utf8'));
delete schema.$schema; // the https draft-07 URI is not registered in ajv; the keywords are draft-07
const ajv = new Ajv({ strict: false, allErrors: true });
const validate = ajv.compile(schema);
const clone = (o) => JSON.parse(JSON.stringify(o));
const cases = [
  ['defaults', (v) => v, true],
  ['pdf tls without trust store', (v) => { v.pdfValidator.enabled = true; v.pdfValidator.existingSecret = 's'; }, false],
  ['pdf tls with trust store', (v) => { v.pdfValidator.enabled = true; v.pdfValidator.existingSecret = 's'; v.sidecarTrustStore.existingSecret = 't'; }, true],
  ['ocr mesh without trust store', (v) => { v.ocr.enabled = true; v.ocr.existingSecret = 's'; v.ocr.transport = 'mesh'; }, true],
  ['ocr enabled without secret', (v) => { v.ocr.enabled = true; v.ocr.transport = 'mesh'; }, false],
  ['bad transport', (v) => { v.ocr.transport = 'plain'; }, false],
  ['teams custom ancestors', (v) => { v.ui.teams.enabled = true; v.ui.teams.frameAncestors = 'https://teams.microsoft.com https://*.example.org:8443'; }, true],
  ['teams http ancestor', (v) => { v.ui.teams.frameAncestors = 'http://x.example'; }, false],
  ['teams non-boolean', (v) => { v.ui.teams.enabled = 'yes'; }, false],
  ['console on', (v) => { v.backup.console.enabled = true; v.backup.console.apiServer.cidrs = ['10.0.0.1/32']; }, true],
  ['console bad cidr', (v) => { v.backup.console.apiServer.cidrs = ['10.0.0.1']; }, false],
  ['production string', (v) => { v.server.production = 'true'; }, false],
];
let rc = 0;
for (const [name, mutate, expect] of cases) {
  const v = clone(base);
  mutate(v);
  const ok = validate(v);
  const pass = ok === expect;
  if (!pass) rc = 1;
  console.log(`${pass ? 'PASS' : 'FAIL'} ${name}: valid=${ok}${ok ? '' : ' ' + JSON.stringify(validate.errors.slice(0, 2).map((e) => e.instancePath + ' ' + e.message))}`);
}
process.exit(rc);
