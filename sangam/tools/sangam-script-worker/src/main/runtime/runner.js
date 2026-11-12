// SPDX-License-Identifier: Apache-2.0
//
// Sangam script harness for JavaScript (ADR-D-8 script sandbox, M-061). Runs INSIDE nsjail: the jail is the security
// boundary (no network, own namespaces, seccomp, cgroup limits); node:vm here only gives the script a clean global
// scope with the BPMN script-task bindings, it is not relied on for isolation.
//
// Protocol (ScriptRunner.java): one JSON object on stdin {script, variables, context, resultVariable}; one JSON object
// on stdout: {ok:true, result, hasValue, value} or {ok:false, bpmnError:{code,message,variables}} or
// {ok:false, error:{type,message}}. The script's console goes to stderr, so stdout carries only the answer.
'use strict';

const vm = require('node:vm');

const answerOut = process.stdout;

function answer(obj) {
  let text;
  try {
    text = JSON.stringify(obj);
  } catch (e) {
    text = JSON.stringify({ ok: false, error: { type: 'ResultError', message: 'result is not JSON: ' + e.message } });
  }
  answerOut.write(text + '\n', () => process.exit(0));
}

class BpmnError extends Error {
  constructor(code, message, variables) {
    super(message || '');
    this.name = 'BpmnError';
    this.code = String(code || '');
    this.variables = variables || {};
  }
}

function readStdin() {
  const chunks = [];
  const fs = require('node:fs');
  const buf = Buffer.alloc(65536);
  for (;;) {
    let n;
    try {
      n = fs.readSync(0, buf, 0, buf.length, null);
    } catch (e) {
      if (e.code === 'EAGAIN') continue;
      if (e.code === 'EOF') break;
      throw e;
    }
    if (n === 0) break;
    chunks.push(Buffer.from(buf.subarray(0, n)));
  }
  return Buffer.concat(chunks).toString('utf8');
}

const IDENT = /^[A-Za-z_$][A-Za-z0-9_$]*$/;
const RESERVED = new Set(['execution', 'variables', 'context', 'BpmnError', 'console']);

async function main() {
  const request = JSON.parse(readStdin());
  const variables = request.variables || {};
  const written = {};
  const log = (...a) => process.stderr.write(a.map(String).join(' ') + '\n');
  const execution = Object.freeze({
    getVariable: (name) => (Object.prototype.hasOwnProperty.call(written, name) ? written[name] : variables[name]),
    hasVariable: (name) => Object.prototype.hasOwnProperty.call(written, name)
      || Object.prototype.hasOwnProperty.call(variables, name),
    setVariable: (name, value) => {
      if (typeof name !== 'string' || !IDENT.test(name)) throw new TypeError('invalid variable name: ' + name);
      written[name] = value;
    },
    getVariables: () => Object.assign({}, variables, written),
    getBusinessKey: () => (request.context || {}).businessKey,
    getProcessInstanceId: () => (request.context || {}).processInstanceId,
  });
  const globals = {
    execution,
    variables: Object.freeze(Object.assign({}, variables)),
    context: Object.freeze(Object.assign({}, request.context || {})),
    BpmnError,
    console: { log, info: log, warn: log, error: log, debug: log },
  };
  // BPMN script tasks read process variables by name; assigning a name does NOT store it (only execution.setVariable
  // and the result variable do), as in the engine's default script bindings.
  for (const [k, v] of Object.entries(variables)) {
    if (IDENT.test(k) && !RESERVED.has(k)) globals[k] = v;
  }
  const sandbox = vm.createContext(globals, { codeGeneration: { strings: false, wasm: false } });
  try {
    let value = new vm.Script(String(request.script), { filename: 'script.js' }).runInContext(sandbox);
    if (value && typeof value.then === 'function') value = await value;
    const hasValue = request.resultVariable != null && value !== undefined;
    answer({ ok: true, result: written, hasValue, value: hasValue ? value : null });
  } catch (e) {
    if (e && (e instanceof BpmnError || e.name === 'BpmnError') && e.code) {
      answer({ ok: false, bpmnError: { code: String(e.code), message: String(e.message || ''),
        variables: e.variables || {} } });
    } else {
      answer({ ok: false, error: { type: (e && e.name) || 'Error', message: String((e && e.message) || e) } });
    }
  }
}

main().catch((e) => answer({ ok: false, error: { type: 'HarnessError', message: String((e && e.message) || e) } }));
