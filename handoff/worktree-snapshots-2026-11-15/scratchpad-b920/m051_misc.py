import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from ed import sub
os.chdir('D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/sangam/sangam-ui/src/modules')
sub('config.tsx', """    { id: 'crosswalk', label: 'act.mapCrosswalk', path: '/config/crosswalks/{crosswalkId}/map', fields: [f.text('crosswalkId', undefined, true), f.text('code', undefined, true), f.money('amount')], showResult: true },""",
"""    // M-051: CrosswalkRequest(scope, office, code, amount, asOf) (ConfigController.java:114); the crosswalk id is the path only
    { id: 'crosswalk', label: 'act.mapCrosswalk', path: '/config/crosswalks/{crosswalkId}/map', fields: [f.path(f.text('crosswalkId', undefined, true)), f.text('code', undefined, true), f.money('amount')], showResult: true },""")
sub('finance.tsx', """    { id: 'close', label: 'act.closePeriod', path: '/ledger/{ledger}/periods/{period}/close', fields: [f.text('period', undefined, true), f.text('nextPeriod'), f.date('asOf')], showResult: true, danger: true, hint: 'mod.ledger.closeHint' },""",
"""    // M-051: CloseRequest(nextPeriod, asOf) (LedgerController.java:108); the period is the path only
    { id: 'close', label: 'act.closePeriod', path: '/ledger/{ledger}/periods/{period}/close', fields: [f.path(f.text('period', undefined, true)), f.text('nextPeriod'), f.date('asOf')], showResult: true, danger: true, hint: 'mod.ledger.closeHint' },""")
