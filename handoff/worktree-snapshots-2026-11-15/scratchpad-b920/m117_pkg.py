import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ed import edit

edit('package.json', [
    ('"@vitejs/plugin-react": "4.7.0"', '"@vitejs/plugin-react": "6.1.2"'),
    ('"vite": "6.4.5"', '"vite": "8.3.5"'),
    ('    "vitest": "4.1.12"\n  }\n}', '    "vitest": "4.1.12"\n  },\n  "overrides": {\n    "rolldown": "1.2.15",\n    "@rolldown/pluginutils": "1.0.1"\n  }\n}'),
])
