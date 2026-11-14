import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ed import sub
os.chdir('D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/sangam/sangam-ui/src')
p='modules/registryNumbering.ts'
sub(p, """    { key: 'fiscalStartMonth', label: 'fld.fiscalStartMonth', description: 'fld.fiscalStartMonthHint', type: 'select', values: [] },
""", """    // json: the select's text '7' is sent as the integer 7 (contract 217C21BE: fiscalStartMonth and fiscalStartDay are integers)
    { key: 'fiscalStartMonth', label: 'fld.fiscalStartMonth', description: 'fld.fiscalStartMonthHint', type: 'select', values: [], json: true },
    fiscalStartDayField(),
""")
sub(p, "import type { FormValues } from '../components/FormRenderer';\n", "import type { FormValues } from '../components/FormRenderer';\nimport { fiscalStartDayField } from './fiscalDay';\n")
p='modules/programmeConfig.ts'
sub(p, """      values: MONTHS.map((m) => ({ value: m, label: `opt.fiscalMonth.${m}` })),
      validate: { required: true },
    },
""", """      values: MONTHS.map((m) => ({ value: m, label: `opt.fiscalMonth.${m}` })),
      validate: { required: true },
      // sent as the integer the contract types (ProgrammePublishConfigBody.fiscalYearStartMonth, contract 217C21BE)
      json: true,
    },
    fiscalStartDayField(),
""")
sub(p, "import type { FormField } from '../api/types';\n", "import type { FormField } from '../api/types';\nimport { fiscalStartDayField } from './fiscalDay';\n")
p='modules/registry.tsx'
sub(p, "fields: [f.json('definition', 'fld.definition', true)], noJson: true, hint: 'mod.registry.publishTypeHint' },", "fields: [f.json('definition', 'fld.definition', true)], noJson: true, hint: 'mod.registry.publishTypeHintFiscal' },")
