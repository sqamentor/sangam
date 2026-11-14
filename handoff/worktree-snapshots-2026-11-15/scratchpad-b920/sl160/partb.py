import os, re, sys, collections, json

root = sys.argv[1]
SAFE = {'concat', 'split', 'substring', 'trim', 'toUpperCase', 'toLowerCase', 'length', 'contains', 'startsWith',
        'endsWith', 'equals', 'isEmpty', 'size', 'get', 'getSingleResult', 'getSingleEntry', 'getResultList',
        'collectEntries'}
IMPLICIT = {'execution', 'task', 'caseExecution', 'externalTask'}
EXEC_OK = {'processBusinessKey', 'businessKey', 'id', 'processInstanceId', 'processDefinitionId', 'activityInstanceId',
           'parentActivityInstanceId', 'caseInstanceId', 'caseExecutionId', 'executionId', 'parentId', 'currentActivityId',
           'activityId', 'taskDefinitionKey', 'tenantId', 'variables', 'variablesLocal', 'variableNames',
           'variableNamesLocal', 'variablesTyped', 'variablesLocalTyped'}
EXEC_METHODS = {'getVariable', 'getVariableLocal', 'getVariableTyped', 'getVariableLocalTyped', 'getVariables',
                'getVariablesLocal', 'getVariablesTyped', 'getVariablesLocalTyped', 'getVariableNames',
                'getVariableNamesLocal', 'hasVariable', 'hasVariableLocal', 'hasVariables', 'hasVariablesLocal',
                'setVariable', 'setVariableLocal', 'setVariables', 'setVariablesLocal', 'removeVariable',
                'removeVariableLocal', 'removeVariables', 'removeVariablesLocal'} | {
                    'get' + p[0].upper() + p[1:] for p in EXEC_OK}
BEANS = {'sangamOnboardingStep': None, 'moveSteps': None}
KEYWORDS = {'and', 'or', 'not', 'eq', 'ne', 'lt', 'gt', 'le', 'ge', 'div', 'mod', 'empty', 'true', 'false', 'null',
            'instanceof'}
tok_re = re.compile(r"\s*(?:('(?:\\.|[^'])*'|\"(?:\\.|[^\"])*\")|(\d+(?:\.\d+)?)|([A-Za-z_]\w*(?::[A-Za-z_]\w*)?)|(\.|\[|\]|\(|\)|,|[^\sA-Za-z0-9_]+))")

def tokens(s):
    out = []
    pos = 0
    while pos < len(s):
        m = tok_re.match(s, pos)
        if not m or m.end() == pos:
            break
        pos = m.end()
        if m.group(1): out.append(('S', m.group(1)))
        elif m.group(2): out.append(('N', m.group(2)))
        elif m.group(3): out.append(('I', m.group(3)))
        else: out.append(('P', m.group(4).strip()))
    return out

def check(expr):
    """returns list of (code, detail)"""
    t = tokens(expr)
    res = []
    i = 0
    def skip_group(j, open_, close_):
        depth = 0
        while j < len(t):
            if t[j] == ('P', open_): depth += 1
            elif t[j] == ('P', close_):
                depth -= 1
                if depth == 0: return j + 1
            j += 1
        return j
    while i < len(t):
        k, v = t[i]
        prev = t[i - 1] if i > 0 else None
        start_chain = (k in ('I', 'S') and not (prev == ('P', '.'))) or (k == 'P' and v == ')')
        if k == 'I' and v in KEYWORDS:
            i += 1; continue
        if k == 'I' and ':' in v or (k == 'I' and i + 1 < len(t) and t[i + 1] == ('P', '(') and prev != ('P', '.')):
            res.append(('FUNCTION', v)); i += 1; continue
        if k in ('I', 'S') and prev != ('P', '.'):
            kind = 'STR' if k == 'S' else ('EXEC' if v in IMPLICIT else ('BEAN' if v in BEANS else 'ROOT'))
            root = v
            j = i + 1
            first = True
            while j < len(t):
                if t[j] == ('P', '.') and j + 1 < len(t) and t[j + 1][0] == 'I':
                    name = t[j + 1][1]
                    call = j + 2 < len(t) and t[j + 2] == ('P', '(')
                    if kind == 'EXEC':
                        ok = (name in EXEC_METHODS) if call else (name in EXEC_OK)
                        if not ok: res.append(('MEMBER', root + '.' + name + ('()' if call else '')))
                    elif kind == 'BEAN':
                        pass
                    elif kind == 'ROOT':
                        if call:
                            if name not in SAFE: res.append(('METHOD', root + '.' + name + '()'))
                        else:
                            res.append(('BEAN', root + '.' + name))
                    else:
                        if call:
                            if name not in SAFE: res.append(('METHOD', '….' + name + '()'))
                        elif name in ('class', 'classLoader', 'declaringClass', 'module'):
                            res.append(('MEMBER', '….' + name))
                    kind = 'VALUE'
                    j = j + 2
                    if call:
                        j = skip_group(j, '(', ')')
                    first = False
                elif t[j] == ('P', '['):
                    if kind in ('EXEC', 'ROOT'):
                        res.append(('BEAN' if kind == 'ROOT' else 'MEMBER', root + '[...]'))
                    kind = 'VALUE'
                    j = skip_group(j, '[', ']')
                else:
                    break
            i = i + 1  # inner tokens of args are checked by continuing the scan
            continue
        i += 1
    return res

def exprs_in(text):
    return [m.group(1) for m in re.finditer(r'[$#]\{((?:[^}\'"]|\'[^\']*\'|"[^"]*")*)\}', text)]

# shipped
ship = json.load(open(os.path.join(os.path.dirname(os.path.abspath(sys.argv[0])), 'inv.json')))['exprs']
bad = collections.Counter()
for e in ship:
    for r in check(e.replace('&amp;', '&').replace('&gt;', '>').replace('&lt;', '<')):
        bad[r] += 1
print('SHIPPED refusals', dict(bad))

# tests: model-like files and java string lines
found = collections.defaultdict(set)
for dp, dn, fn in os.walk(root):
    dn[:] = [d for d in dn if not d.startswith('target') and d not in ('node_modules', '.git')]
    for f in fn:
        p = os.path.join(dp, f).replace(chr(92), '/')
        if '/src/test/' not in p:
            continue
        if not f.endswith(('.java', '.bpmn', '.model', '.tpl', '.cmmn', '.xml')):
            continue
        try:
            txt = open(p, encoding='utf-8').read()
        except Exception:
            continue
        for line in txt.splitlines():
            if False:
                continue
            for e in exprs_in(line):
                e2 = e.replace('&amp;', '&').replace('&gt;', '>').replace('&lt;', '<').replace('\\"', '"')
                for r in check(e2):
                    found[r].add(p[len(root):].lstrip('/'))
print('TEST refusals:')
for r, ps in sorted(found.items()):
    print(' ', r, sorted(ps)[:4], len(ps))
