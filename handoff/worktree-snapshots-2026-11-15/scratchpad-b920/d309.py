import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ed import edit

edit('README.md', [(
    'and loads only in the modeller.\n\n## Runtime configuration',
    'and loads only in the modeller.\n\n'
    '**Supported browsers (owner decision 309):** Chrome and Edge 111 or later, Firefox 114 or later, Safari 16.4 or later. '
    'This is the Vite 8 default build target (`baseline-widely-available`), and `vite.config.ts` deliberately sets no '
    '`build.target` override. Older browsers are not supported: the built JavaScript and CSS may use syntax they cannot parse. '
    'The same baseline applies to the embeddable web components (`npm run build:embed`).\n\n'
    '## Runtime configuration',
)])

edit('scripts/list-licences.mjs', [(
    "const OSI = new Set(['MIT', 'ISC',",
    "// MIT-0 matches deploy/licence/licence-policy.json (coordinator ruling 2026-11-13).\n"
    "const OSI = new Set(['MIT', 'MIT-0', 'ISC',",
)])
