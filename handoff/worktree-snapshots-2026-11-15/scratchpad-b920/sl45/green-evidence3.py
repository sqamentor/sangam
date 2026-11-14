import sys, json
n = 0
with open(sys.argv[1], encoding="utf-8", errors="replace") as f:
    for line in f:
        if "ENGINE-14006" in line and "no authenticated tenant" in line:
            e = json.loads(line); m = e.get("message", "")
            print(e.get("@timestamp"), (e.get("process") or {}).get("thread", {}).get("name"), "|", m[:420].replace("\n", " "))
            n += 1
            if n >= 2: break
