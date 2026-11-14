import re, sys, os
rules = {
 'aws-access-key': re.compile(rb'\b(AKIA|ASIA)[0-9A-Z]{16}\b'),
 'private-key': re.compile(rb'-----BEGIN (RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----'),
 'github-token': re.compile(rb'\bgh[pousr]_[A-Za-z0-9]{36,}\b'),
 'slack-token': re.compile(rb'\bxox[baprs]-[A-Za-z0-9-]{10,}'),
 'google-api-key': re.compile(rb'\bAIza[0-9A-Za-z_\-]{35}\b'),
 'stripe-live': re.compile(rb'\b(sk|rk)_live_[0-9A-Za-z]{16,}'),
 'jwt': re.compile(rb'\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}'),
 'anthropic-key': re.compile(rb'\bsk-ant-[A-Za-z0-9_-]{20,}'),
 'openai-key': re.compile(rb'\bsk-(proj-)?[A-Za-z0-9]{32,}'),
 'generic-assign': re.compile(rb'(?i)(password|passwd|secret|api[_-]?key|access[_-]?token|client[_-]?secret)\s*[:=]\s*["\']([^"\'\s$<{][^"\'\s]{11,})["\']'),
}
hits = 0
for f in open(sys.argv[1], encoding='utf-8').read().split('\n'):
    if not f or not os.path.isfile(f): continue
    if os.path.getsize(f) > 5_000_000: continue
    data = open(f, 'rb').read()
    if b'\0' in data[:4096]: continue
    for n, line in enumerate(data.split(b'\n'), 1):
        for name, rx in rules.items():
            m = rx.search(line)
            if m:
                val = m.group(m.lastindex or 0)
                hits += 1
                print(f"{f}:{n} {name} len={len(val)}")
print("hits", hits)
