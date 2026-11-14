import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ed import sub
os.chdir('D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/sangam/sangam-ui/src')
p = 'modules/programme.tsx'
sub(p, """  columns: [c('version'), c('publishedAt'), c('publishedBy'), c('hash')],
  rowKey: ['version'],
  // M-051: the config keys the server reads include programmeKinds (ProgrammeConfig.java:25, :58)
  toolbar: [{ id: 'publish', label: 'act.publish', method: 'PUT', path: '/programme-config', fields: [f.json('indicators', undefined, true), f.json('returns'), f.json('programmeKinds', 'fld.kinds')], hint: 'mod.programmes.configHint' }],""", """  // history rows: version, contentHash, current, definedBy, definedAt (IndicatorRegistry.java:167-171)
  columns: [c('version'), c('current'), c('definedAt', 'fld.definedAt'), c('definedBy', 'fld.definedBy'), c('contentHash', 'fld.contentHash')],
  rowKey: ['version'],
  // X1-01 / M-051: fiscalYearStartMonth is required (ProgrammeConfig.java:221-235); programmeKinds (:58); see programmeConfig.ts
  toolbar: [{ id: 'publish', label: 'act.publish', method: 'PUT', path: '/programme-config', fields: programmeConfigFields(), hint: 'mod.programmes.configHint' }],""")
sub(p, "import { ModulePage, c, f, type ScreenDef } from './engine';", "import { ModulePage, c, f, type ScreenDef } from './engine';\nimport { programmeConfigFields } from './programmeConfig';")
