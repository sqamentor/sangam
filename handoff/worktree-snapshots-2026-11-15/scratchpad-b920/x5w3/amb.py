import json,os
d=json.load(open(os.environ['X5']+r'\pairs.json'))
R=r'D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524'
for r in d['res']:
    if len(r['cand'])!=1:
        print(r['line'],r['w'],r['tok'],len(r['cand']),[c.replace(R,'')[:110] for c in r['cand'][:4]])
