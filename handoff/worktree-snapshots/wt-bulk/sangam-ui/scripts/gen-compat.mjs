// SPDX-License-Identifier: Apache-2.0
// R46 modeler/runtime compatibility matrix generator.
//
// Inputs:
//   - modeller palettes: bpmn-js PaletteProvider + ReplaceOptions, dmn-js-drd PaletteProvider, the dmn-js decision-table
//     hit policies, and the bpmn / dmn moddle descriptors (every construct the editors can author);
//   - engine support: Operaton 2.2 BpmnParse.java and DefaultCmmnElementHandlerRegistry.java from ../../reference/operaton
//     when present (study checkout), otherwise the previous matrix's engine column is kept;
//   - Sangam rules: scripts/compat-rules.json (deploy-time rejections, ports, limits; each with its source).
// Outputs:
//   - src/modeller/compatibility.json (shown in the modeller, used by the pre-deploy check);
//   - test-plan/R46-ROUND-TRIP-TESTS.md (the round-trip test list for the testing phase).
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const nm = join(root, 'node_modules');
const ref = join(root, '..', '..', 'reference', 'operaton');
const read = (p) => (existsSync(p) ? readFileSync(p, 'utf8') : '');
const rules = JSON.parse(readFileSync(join(root, 'scripts', 'compat-rules.json'), 'utf8'));
const outJson = join(root, 'src', 'modeller', 'compatibility.json');
const previous = existsSync(outJson) ? JSON.parse(readFileSync(outJson, 'utf8')) : { rows: [] };
const prevEngine = new Map(previous.rows.map((r) => [`${r.notation}:${r.construct}`, r.engine]));

const lower = (s) => s.charAt(0).toLowerCase() + s.slice(1);
const uniq = (a) => [...new Set(a)];

// ---------------------------------------------------------------- BPMN palette
const palette = read(join(nm, 'bpmn-js/lib/features/palette/PaletteProvider.js'));
const replace = read(join(nm, 'bpmn-js/lib/features/replace/ReplaceOptions.js'));
const bpmnPalette = uniq([...palette.matchAll(/'bpmn:(\w+)'/g)].map((m) => m[1]));
const bpmnReplace = uniq([...replace.matchAll(/(?:type|eventDefinitionType): 'bpmn:(\w+)'/g)].map((m) => m[1]));
const bpmnModdle = JSON.parse(read(join(nm, 'bpmn-moddle/resources/bpmn/json/bpmn.json')) || '{"types":[]}');
const moddleTypes = new Set(bpmnModdle.types.map((t) => t.name));
const extra = ['AdHocSubProcess', 'MultiInstanceLoopCharacteristics', 'StandardLoopCharacteristics', 'TextAnnotation', 'MultipleEventDefinition', 'SubProcess.triggeredByEvent'];
const bpmnAuthored = uniq([...bpmnPalette, ...bpmnReplace, ...extra]).filter((n) => moddleTypes.has(n.split('.')[0]) || n.includes('.'));

// ---------------------------------------------------------------- BPMN engine (Operaton BpmnParse)
const bpmnParse = read(join(ref, 'engine/src/main/java/org/operaton/bpm/engine/impl/bpmn/parser/BpmnParse.java'));
function engineParsesBpmn(name) {
  if (!bpmnParse) return undefined;
  const local = lower(name.split('.')[0]);
  return bpmnParse.includes(`"${local}"`) || bpmnParse.includes(`parse${name.split('.')[0]}(`);
}

// ---------------------------------------------------------------- DMN palette / engine
const drd = read(join(nm, 'dmn-js-drd/lib/features/palette/PaletteProvider.js'));
const dmnPalette = uniq([...drd.matchAll(/'dmn:(\w+)'/g)].map((m) => m[1]));
const dmnAuthored = uniq([
  ...dmnPalette,
  'DecisionService',
  'LiteralExpression',
  'Context',
  'Relation',
  'Invocation',
  'FunctionDefinition',
  ...['UNIQUE', 'FIRST', 'ANY', 'COLLECT', 'RULE ORDER', 'PRIORITY', 'OUTPUT ORDER'].map((h) => `HitPolicy.${h}`),
]);
const dmnHandlers = read(join(ref, 'engine-dmn/engine/src/main/java/org/operaton/bpm/dmn/engine/impl/transform/DefaultElementTransformHandlerRegistry.java'));
/** DMN element executed when the engine registers a transform handler for it (InputData is a requirement only). */
function engineDmn(name) {
  if (!dmnHandlers) return undefined;
  if (name === 'InputData' || name === 'KnowledgeSource') return dmnHandlers.includes('Definitions.class');
  return dmnHandlers.includes(`${name}.class`);
}
const hitRegistry = read(join(ref, 'engine-dmn/engine/src/main/java/org/operaton/bpm/dmn/engine/impl/hitpolicy/DefaultHitPolicyHandlerRegistry.java'));

// ---------------------------------------------------------------- CMMN (no bpmn.io editor: the Sangam source editor)
const cmmnRegistry = read(join(ref, 'engine/src/main/java/org/operaton/bpm/engine/impl/cmmn/handler/DefaultCmmnElementHandlerRegistry.java'));
const cmmnAuthored = Object.keys(rules.cmmn);
function engineCmmn(name) {
  if (!cmmnRegistry) return undefined;
  const active = cmmnRegistry.split('\n').filter((l) => !/^\s*\/\//.test(l)).join('\n');
  return active.includes(`${name}.class`) || !/Task|Stage|Milestone|EventListener|CasePlanModel/.test(name);
}

function row(notation, construct, authored, engine, rule) {
  const key = `${notation}:${construct}`;
  const eng = engine === undefined ? prevEngine.get(key) ?? 'UNKNOWN' : engine ? 'PARSED' : 'NOT_PARSED';
  const status = rule?.status ?? (eng === 'PARSED' ? 'SUPPORTED' : eng === 'NOT_PARSED' ? 'UNSUPPORTED' : 'UNKNOWN');
  return { notation, construct, modeller: authored, engine: eng, status, note: rule?.note ?? '', source: rule?.source ?? (eng === 'PARSED' ? 'Operaton parser' : '') };
}

const rows = [];
for (const c of uniq([...bpmnAuthored, ...Object.keys(rules.bpmn)])) {
  const authored = bpmnPalette.includes(c) ? 'PALETTE' : bpmnReplace.includes(c) ? 'REPLACE_MENU' : bpmnAuthored.includes(c) ? 'PROPERTIES' : 'IMPORT_ONLY';
  rows.push(row('BPMN', c, authored, engineParsesBpmn(c), rules.bpmn[c]));
}
for (const c of uniq([...dmnAuthored, ...Object.keys(rules.dmn)])) {
  const authored = dmnPalette.includes(c) ? 'PALETTE' : c.startsWith('HitPolicy.') || c === 'LiteralExpression' ? 'PROPERTIES' : 'IMPORT_ONLY';
  const eng = c.startsWith('HitPolicy.') ? (hitRegistry ? hitRegistry.includes(c.split('.')[1].replace(' ', '_')) : undefined) : engineDmn(c);
  rows.push(row('DMN', c, authored, eng, rules.dmn[c]));
}
for (const c of cmmnAuthored) rows.push(row('CMMN', c, 'SOURCE_EDITOR', engineCmmn(c), rules.cmmn[c]));
rows.sort((a, b) => a.notation.localeCompare(b.notation) || a.construct.localeCompare(b.construct));

/** XML element name(s) used by the pre-deploy scan to find a construct in a model file. */
function xmlNames(r) {
  const base = r.construct.split('.')[0];
  if (r.construct === 'SubProcess.triggeredByEvent') return [];
  if (r.construct.startsWith('HitPolicy.')) return [];
  return [lower(base)];
}
const matrix = {
  generatedAt: new Date().toISOString(),
  inputs: {
    bpmnPalette: bpmnPalette.length,
    bpmnReplaceMenu: bpmnReplace.length,
    operatonBpmnParse: !!bpmnParse,
    operatonCmmnRegistry: !!cmmnRegistry,
    operatonHitPolicies: !!hitRegistry,
  },
  rows: rows.map((r) => ({ ...r, xml: xmlNames(r) })),
};
mkdirSync(dirname(outJson), { recursive: true });
writeFileSync(outJson, JSON.stringify(matrix, null, 2) + '\n');

// ---------------------------------------------------------------- round-trip test list (R46, T20, T32)
const lines = [
  '# R46 round-trip test list (generated)',
  '',
  'Generated by `scripts/gen-compat.mjs` from the compatibility matrix. Run in the testing phase. Each row is one test:',
  '',
  '1. **Author**: create the construct in the Sangam modeller (palette, replace menu, properties panel or CMMN source editor), or import the fixture.',
  '2. **Round-trip**: save, reload, export, re-import. The XML must be semantically equal (same element, attributes and extension elements; ids stable).',
  '3. **Deploy**: deploy to the sandbox. The expected result is in the "Deploy" column.',
  '4. **Runtime**: for executable rows, run the scenario and compare the history with the expected path.',
  '5. **UI warning**: the pre-deploy check in the package workspace shows the matrix status before deploy.',
  '',
  '| # | Notation | Construct | Authored via | Deploy | Runtime check | Note |',
  '|---|---|---|---|---|---|---|',
];
let n = 0;
for (const r of matrix.rows) {
  n += 1;
  const deploy = r.status === 'REJECTED' ? 'must fail with the rule message' : r.status === 'UNSUPPORTED' ? 'must fail or be flagged non-executable' : r.status === 'NOT_EXECUTABLE' ? 'deploys; ignored at runtime' : 'must succeed';
  const run = r.status === 'SUPPORTED' || r.status === 'SUPPORTED_WITH_RULES' || r.status === 'PORTED' ? 'scenario passes' : 'n/a';
  lines.push(`| ${n} | ${r.notation} | ${r.construct} | ${r.modeller} | ${deploy} | ${run} | ${r.note.replace(/\|/g, '/')} |`);
}
lines.push('', '**Also required (R46):**', '', '- MIWG reference models import and export unchanged (syntactic interchange, kept separate from runtime equivalence, R44).', '- Flowable-authored models: run the import assessment (EC-21) and compare it with this matrix.', '- Hindi labels (`i18n.hi`) in forms survive a round-trip.', '- Watermark visible in every editor after reload (LIC-8).', '');
mkdirSync(join(root, 'test-plan'), { recursive: true });
writeFileSync(join(root, 'test-plan', 'R46-ROUND-TRIP-TESTS.md'), lines.join('\n'));
console.log(`compatibility matrix: ${matrix.rows.length} constructs (BPMN ${rows.filter((r) => r.notation === 'BPMN').length}, DMN ${rows.filter((r) => r.notation === 'DMN').length}, CMMN ${rows.filter((r) => r.notation === 'CMMN').length}); engine sources: ${JSON.stringify(matrix.inputs)}`);
