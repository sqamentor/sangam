p = 'D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/sangam/sangam-ui/src/modules/platform.tsx'
s = open(p, encoding='utf8', newline='').read()
crlf = '\r\n' in s
s = s.replace('\r\n', '\n')
i = s.index('/** Bulk test report: non-deterministic (judge) results are kept apart and never affect the pass count. */')
j = s.index('// ------------------------------------------------------------------ ops', i)
new = open('C:/Users/LOKEND~2/AppData/Local/Temp/claude/b920001b93f0ae3b/6e98c2a7-cdac-4783-9098-832546572812/scratchpad/tests-new.tsx', encoding='utf8').read()
s = s[:i] + new + '\n' + s[j:]
old_imp = "import { BpmnDiagram } from '../components/BpmnDiagram';\n"
assert s.count(old_imp) == 1
s = s.replace(old_imp, old_imp + "import { fmtNumber } from '../i18n';\n")
if crlf:
    s = s.replace('\n', '\r\n')
open(p, 'w', encoding='utf8', newline='').write(s)
print('ok', crlf)
