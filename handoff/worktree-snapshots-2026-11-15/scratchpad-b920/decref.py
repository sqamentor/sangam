import re, json, os, collections
S = os.path.dirname(os.path.abspath(__file__))
H = r"D:\GITHUB  Project\sangam-handoff"
E = json.load(open(os.path.join(S, "entries.json"), encoding="utf-8"))
NUM = r"\d{1,3}[A-Z]?"
LIST = r"(?:%s)(?:\s*(?:,|/|\+|and|&|to|-|\.\.|or)?\s*(?:%s)\b)*" % (NUM, NUM)
pat = re.compile(r"(?i)\b(?:owner\s+)?decisions?\s*(?:#|no\.?\s*)?(" + LIST + ")")
rng = re.compile(r"(\d{1,3})\s*(?:-|to|\.\.)\s*(\d{1,3})")
paren = re.compile(r"\((\d{2,3})\)")
def nums(text):
    out = set()
    for m in pat.finditer(text):
        s = m.group(1)
        for a, b in rng.findall(s):
            a, b = int(a), int(b)
            if a < b <= 303 and b - a <= 40:
                out.update(range(a, b + 1))
        for n in re.findall(r"\d{1,3}", s):
            n = int(n)
            if 1 <= n <= 303: out.add(n)
    # lane-specific shorthand "(82)" used by W3-G etc
    for n in paren.findall(text):
        n = int(n)
        if 25 <= n <= 303: out.add(("p", n))
    return out
ref = collections.defaultdict(list)
for e in E:
    for n in nums(e["text"]):
        ref[n].append(e)
res = {}
for n in range(1, 304):
    hits = ref.get(n, [])
    ph = ref.get(("p", n), [])
    done = [h for h in hits if "DONE" in h["type"]]
    res[n] = dict(all=len(hits), done=len(done), pdone=len([h for h in ph if "DONE" in h["type"]]),
                  last_done=(done[-1]["board"] + " " + done[-1]["ts"] + " " + done[-1]["who"] + ": " + done[-1]["text"][:200]) if done else "",
                  other=[(h["board"], h["ts"], h["who"], h["type"], h["text"][:160]) for h in hits[-3:]])
json.dump(res, open(os.path.join(S, "decref.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=0)
nod = [n for n in range(1, 300) if res[n]["done"] == 0]
print("no DONE:", len(nod))
print(nod)
print("no mention at all:", [n for n in nod if res[n]["all"] == 0])
