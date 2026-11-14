import json, sys, pathlib
root = pathlib.Path(sys.argv[1])
dirs = sys.argv[2].split(',')
for d in dirs:
    m = json.loads((root / d / 'pack.json').read_text(encoding='utf-8'))
    print('##', m['id'], m['mainProcesses'])
    for a in m.get('acceptance', []):
        print('  ', a['id'], 'vars=', a.get('startVariables'), 'state=', a.get('expectState'), 'run=', json.dumps(a.get('run'))[:200])
