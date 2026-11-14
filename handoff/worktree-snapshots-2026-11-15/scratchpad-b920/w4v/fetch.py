import sys, subprocess, hashlib, os, datetime
url, name = sys.argv[1], sys.argv[2]
log = lambda k, u, t: subprocess.run([sys.executable, 'log.py', k, u, t], check=True, stdout=subprocess.DEVNULL)
log('PLANNED', url, 'decision 288 primary-text read: fetch one official PDF (GET only) into evidence/statutory/' + name + ', never executed, text extracted with Python -I')
d = r'D:\GITHUB  Project\sangam-handoff\evidence\statutory'
p = os.path.join(d, name)
r = subprocess.run(['curl', '-sS', '-L', '--max-time', '120', '-A', 'Mozilla/5.0', '-o', p, '-w', '%{http_code} %{content_type} %{url_effective}', url], capture_output=True, text=True)
info = r.stdout + ' ' + r.stderr.strip()
if os.path.exists(p) and open(p, 'rb').read(5) == b'%PDF-':
    h = hashlib.sha256(open(p, 'rb').read()).hexdigest()
    log('RESULT', url, f'PDF saved {name}, {os.path.getsize(p)} B, SHA-256 {h}; curl {info}')
    print('OK', name, os.path.getsize(p), h)
else:
    if os.path.exists(p): os.remove(p)
    log('RESULT', url, f'BLOCKED/not a PDF, nothing kept; curl {info}')
    print('FAIL', name, info)
