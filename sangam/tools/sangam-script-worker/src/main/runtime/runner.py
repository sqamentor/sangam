# SPDX-License-Identifier: Apache-2.0
#
# Sangam script harness for Python (ADR-D-8 script sandbox, M-061). Runs INSIDE nsjail with `python3 -I -S`: the jail
# is the security boundary (no network, own namespaces, seccomp, cgroup limits); this file only gives the script the
# BPMN script-task bindings and speaks the protocol of ScriptRunner.java:
#   stdin : one JSON object {script, variables, context, resultVariable}
#   stdout: one JSON object {ok: true, result, hasValue, value}
#           or {ok: false, bpmnError: {code, message, variables}} or {ok: false, error: {type, message}}
# Numbers with a fraction arrive as decimal.Decimal and leave as exact JSON numbers (no binary floating point for
# amounts). The script's print() goes to stderr, so stdout carries only the answer.
import ast
import decimal
import json
import math
import re
import sys

_ANSWER = sys.stdout
_IDENT = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
_RESERVED = {"execution", "variables", "context", "BpmnError"}


class BpmnError(Exception):
    def __init__(self, code, message="", variables=None):
        super().__init__(message)
        self.code = str(code or "")
        self.message = str(message or "")
        self.variables = dict(variables or {})


class _Execution:
    def __init__(self, variables, context, written):
        self._variables = variables
        self._context = context
        self._written = written

    def getVariable(self, name):
        return self._written[name] if name in self._written else self._variables.get(name)

    def hasVariable(self, name):
        return name in self._written or name in self._variables

    def setVariable(self, name, value):
        if not isinstance(name, str) or not _IDENT.match(name):
            raise TypeError("invalid variable name: %r" % (name,))
        self._written[name] = value

    def getVariables(self):
        merged = dict(self._variables)
        merged.update(self._written)
        return merged

    def getBusinessKey(self):
        return self._context.get("businessKey")

    def getProcessInstanceId(self):
        return self._context.get("processInstanceId")


def _encode(value):
    """JSON text with Decimal kept exact; rejects what JSON cannot carry."""
    if value is None or isinstance(value, bool):
        return json.dumps(value)
    if isinstance(value, int):
        return str(value)
    if isinstance(value, decimal.Decimal):
        if not value.is_finite():
            raise ValueError("not a finite number: %s" % value)
        return format(value, "f")
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValueError("not a finite number: %r" % value)
        return repr(value)
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, dict):
        return "{" + ",".join(json.dumps(str(k), ensure_ascii=False) + ":" + _encode(v) for k, v in value.items()) + "}"
    if isinstance(value, (list, tuple)):
        return "[" + ",".join(_encode(v) for v in value) + "]"
    raise TypeError("a %s cannot be a process variable" % type(value).__name__)


def _answer(obj):
    try:
        text = _encode(obj)
    except (TypeError, ValueError) as e:
        text = _encode({"ok": False, "error": {"type": "ResultError", "message": "result is not JSON: %s" % e}})
    _ANSWER.write(text + "\n")
    _ANSWER.flush()


def _run(request):
    variables = request.get("variables") or {}
    context = request.get("context") or {}
    written = {}
    scope = {
        "__name__": "__script__",
        "__builtins__": __builtins__,
        "execution": _Execution(variables, context, written),
        "variables": dict(variables),
        "context": dict(context),
        "BpmnError": BpmnError,
    }
    # BPMN script tasks read process variables by name; assigning a name does NOT store it (only
    # execution.setVariable and the result variable do), as in the engine's default script bindings.
    for k, v in variables.items():
        if _IDENT.match(k) and k not in _RESERVED:
            scope[k] = v
    tree = ast.parse(request.get("script") or "", filename="script.py", mode="exec")
    last = None
    if tree.body and isinstance(tree.body[-1], ast.Expr):
        last = ast.Expression(tree.body.pop().value)
    exec(compile(tree, "script.py", "exec"), scope)
    value = eval(compile(last, "script.py", "eval"), scope) if last is not None else None
    has_value = request.get("resultVariable") is not None and last is not None
    return {"ok": True, "result": written, "hasValue": has_value, "value": value if has_value else None}


def main():
    sys.stdout = sys.stderr  # print() in the script must not reach the answer channel
    try:
        request = json.loads(sys.stdin.buffer.read().decode("utf-8"), parse_float=decimal.Decimal)
        result = _run(request)
    except BpmnError as e:
        result = {"ok": False, "bpmnError": {"code": e.code, "message": e.message, "variables": e.variables}}
        if not e.code:
            result = {"ok": False, "error": {"type": "BpmnError", "message": "a BpmnError needs a code"}}
    except BaseException as e:  # SystemExit and KeyboardInterrupt too: the script decides nothing about the protocol
        result = {"ok": False, "error": {"type": type(e).__name__, "message": str(e)[:1000]}}
    _answer(result)


if __name__ == "__main__":
    main()
