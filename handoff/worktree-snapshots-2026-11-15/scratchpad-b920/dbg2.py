import os
S=r"C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-dst"
I=r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524"
same=diff=miss=0; ex=[]
for root,dirs,files in os.walk(S):
    dirs[:]=[d for d in dirs if d not in {"target","node_modules",".git","out","build","dist"}]
    for f in files:
        rel=os.path.relpath(os.path.join(root,f),S)
        p=os.path.join(I,rel)
        if not os.path.exists(p): miss+=1; ex.append(rel) if len(ex)<8 else None; continue
        a=open(os.path.join(root,f),'rb').read().replace(b'\r\n',b'\n'); b=open(p,'rb').read().replace(b'\r\n',b'\n')
        if a==b: same+=1
        else: diff+=1
print(same,diff,miss,ex)
