import collections,os
L=open(os.path.join(os.path.dirname(__file__),'xor.txt'),encoding='utf-8').read().splitlines()
print(len(L))
c=collections.Counter(os.path.normpath(l.split('|')[0]).split(os.sep)[:3].__repr__() for l in L)
for k,v in c.most_common(15): print(v,k)
print('uncond', sum('uncond_nondefault=True' in l for l in L))
