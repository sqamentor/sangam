import os,re,collections,html
D=os.path.dirname(__file__)
L=[l for l in open(os.path.join(D,'xor.txt'),encoding='utf-8').read().splitlines()
   if os.sep+'target' not in os.path.normpath(l.split('|')[0]).split('src')[0] and ('src' in l.split('|')[0] or 'corpus' in l.split('|')[0])]
L=[l for l in L if not re.search(r'[\/]target[^\/]*[\/]', l.split('|')[0])]
EQ=re.compile(r"^\$\{\s*(\w+(?:\.\w+)*)\s*(==|eq)\s*('[^']*'|\"[^\"]*\"|-?\d+(?:\.\d+)?|true|false|null)\s*\}$")
NE=re.compile(r"^\$\{\s*(\w+(?:\.\w+)*)\s*(!=|ne)\s*('[^']*'|\"[^\"]*\"|-?\d+(?:\.\d+)?|true|false|null)\s*\}$")
CMP=re.compile(r"^\$\{\s*(\w+(?:\.\w+)*)\s*(<=|>=|<|>|lt|gt|le|ge)\s*(-?\d+(?:\.\d+)?)\s*\}$")
BOOL=re.compile(r"^\$\{\s*(!|not\s+)?\s*(\w+(?:\.\w+)*)\s*\}$")
ops={'lt':'<','gt':'>','le':'<=','ge':'>='}
def interval(op,v):
    return {'<':(float('-inf'),v,False,False),'<=':(float('-inf'),v,False,True),'>':(v,float('inf'),False,False),'>=':(v,float('inf'),True,False)}[op]
def ov(a,b):
    lo=max(a[0],b[0]); hi=min(a[1],b[1])
    if lo<hi: return True
    if lo>hi: return False
    # touching point
    inA = (a[0]<lo or (a[0]==lo and a[2])) and (a[1]>lo or (a[1]==lo and a[3]))
    inB = (b[0]<lo or (b[0]==lo and b[2])) and (b[1]>lo or (b[1]==lo and b[3]))
    return inA and inB
res=collections.defaultdict(list)
for l in L:
    f,g,d,pol,_,rest=l.split('|',5)
    d=d.split('=',1)[1]
    flows=[x.split(': ',1) for x in rest.split(' ;; ')]
    conds=[(i,html.unescape(c)) for i,c in flows if i!=d and c not in ('None',None)]
    kind='review'
    if len(conds)<2: kind='single'
    else:
        e=[EQ.match(c) for _,c in conds]
        if all(e) and len({m.group(1) for m in e})==1:
            vals=[m.group(3).strip("'\"") for m in e]
            kind='disjoint-eq' if len(set(vals))==len(vals) else 'OVERLAP-eq'
        else:
            b=[BOOL.match(c) for _,c in conds]
            if all(b) and len(conds)==2 and len({m.group(2) for m in b})==1 and bool(b[0].group(1))!=bool(b[1].group(1)):
                kind='disjoint-bool'
            else:
                # x == v  vs x != v pair
                if len(conds)==2:
                    m1,m2=EQ.match(conds[0][1]) or NE.match(conds[0][1]),EQ.match(conds[1][1]) or NE.match(conds[1][1])
                    if m1 and m2 and m1.group(1)==m2.group(1) and m1.group(3)==m2.group(3) and m1.group(2)!=m2.group(2):
                        kind='disjoint-eqne'
                c=[CMP.match(x) for _,x in conds]
                if kind=='review' and all(c) and len({m.group(1) for m in c})==1:
                    iv=[interval(ops.get(m.group(2),m.group(2)),float(m.group(3))) for m in c]
                    kind='disjoint-range' if not any(ov(iv[i],iv[j]) for i in range(len(iv)) for j in range(i+1,len(iv))) else 'OVERLAP-range'
    res[kind].append((f,g,conds))
for k,v in sorted(res.items()): print(k,len(v))
with open(os.path.join(D,'review.txt'),'w',encoding='utf-8') as o:
    for k in sorted(res):
        if k.startswith('disjoint') or k=='single': continue
        for f,g,c in res[k]:
            o.write(f"{k}|{f}|{g}|"+" ;; ".join(f"{i}: {x}" for i,x in c)+"\n")
