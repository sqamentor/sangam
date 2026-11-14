import sys, datetime
kind, url, text = sys.argv[1], sys.argv[2], sys.argv[3]
t = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
if kind == 'PLANNED':
    line = f"{t} | W4-V (decision 288 primary-text read) | PLANNED read-only, no install, no login, no form: {text} | {url} | - | PLANNED\n"
else:
    line = f"{t} | W4-V (decision 288 primary-text read) | RESULT {text} | {url} | - | RESULT\n"
open(r'D:\GITHUB  Project\sangam-handoff\DOWNLOADS-WAVE4.md','a',encoding='utf-8').write(line)
print(line)
