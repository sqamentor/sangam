import re,subprocess,sys,os
files=subprocess.run(['git','grep','-l','-E','SangamEngineFactory\.build|SangamEngineConfigurer|EngineWiring|SangamEngineSettings'],capture_output=True,text=True,cwd='.').stdout.split()
files=[f for f in files if '/src/test/' in f and f.startswith('sangam/') and f.endswith('.java')]
for f in files:
    src=open(f,encoding='utf-8').read().split('\n')
    # find settings variable names
    vars=set(re.findall(r'SangamEngineSettings\s+(\w+)\s*=',"\n".join(src)))
    vars|=set(re.findall(r'(\w+)\s*=\s*new SangamEngineSettings\(',"\n".join(src)))
    if not vars: continue
    built=None
    for i,l in enumerate(src):
        if re.search(r'SangamEngineFactory\.build|\.configure\(|buildProcessEngine\(|EngineWiring',l):
            built=i
        for v in vars:
            m=re.search(r'\b'+v+r'\.(set\w+|get\w+\(\)\.(put|add|remove|clear|putAll|addAll))\(',l)
            if m and built is not None and i>built and i-built<200:
                print(f"{f}:{i+1}: (build at {built+1}) {l.strip()[:140]}")
