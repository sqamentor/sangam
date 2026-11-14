import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ed import sub
os.chdir('D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/sangam/sangam-ui/src')
p = 'modules/compliance.tsx'
sub(p, "f.select('basis', ['CONSENT', 'LEGITIMATE_USE', 'LEGAL_OBLIGATION', 'STATE_FUNCTION'], 'fld.lawfulBasis', true)", "f.text('basis', 'fld.lawfulBasis', true, { description: 'mod.compliance.basisHint' })")
sub(p, "  columns: [c('code'), c('name'), c('lawfulBasis'), c('retention'), c('active')],", "  // PurposeDefinition fields (PurposeDefinition.java:27): the basis is basis, not lawfulBasis\n  columns: [c('code'), c('names', 'fld.names'), c('basis', 'fld.lawfulBasis'), c('retention'), c('active')],")
sub(p, "// basis code of the tenant's jurisdiction pack (PurposeBindingGuard.java:64-65)", "// basis code of the tenant's jurisdiction pack (PurposeBindingGuard.java:64-65); owner 2026-11-12 #49: no fixed list in the UI")
