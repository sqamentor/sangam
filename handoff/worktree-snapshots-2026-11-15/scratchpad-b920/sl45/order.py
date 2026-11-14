import sys, re
order = []
with open(sys.argv[1], encoding="utf-8", errors="replace") as f:
    for line in f:
        m = re.search(r"\[(INFO|ERROR|WARNING)\] (Running (org\.sangam\.server\.\S+)|Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+), Time elapsed: ([\d.]+) s.*? in (org\.sangam\.server\.\S+))", line)
        if m:
            if m.group(3):
                order.append(m.group(3))
            else:
                print(f"{m.group(9):80s} run={m.group(4)} F={m.group(5)} E={m.group(6)} t={m.group(8)}")
