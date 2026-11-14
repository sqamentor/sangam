import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ed import edit

edit('vite.config.ts', [
    ("      rollupOptions: {\n", "      // M-117 (vite 8): rolldown replaces rollup and esbuild; build.rollupOptions became build.rolldownOptions.\n      rolldownOptions: {\n"),
    ("        output: {\n"
     "          manualChunks: {\n"
     "            react: ['react', 'react-dom', 'react-router-dom'],\n"
     "            i18n: ['i18next', 'react-i18next'],\n"
     "          },\n"
     "        },\n",
     "        output: {\n"
     "          // Rolldown has no object form of manualChunks. The same two vendor chunks as before, now as code-splitting\n"
     "          // groups: each lists the packages and their runtime-only dependencies that the old object form pulled in.\n"
     "          codeSplitting: {\n"
     "            groups: [\n"
     "              {\n"
     "                name: 'react',\n"
     "                test: /[\\\\/]node_modules[\\\\/](react|react-dom|scheduler|react-router|react-router-dom|cookie|set-cookie-parser)[\\\\/]/,\n"
     "                priority: 20,\n"
     "              },\n"
     "              {\n"
     "                name: 'i18n',\n"
     "                test: /[\\\\/]node_modules[\\\\/](i18next|react-i18next|html-parse-stringify|void-elements)[\\\\/]/,\n"
     "                priority: 10,\n"
     "              },\n"
     "            ],\n"
     "          },\n"
     "        },\n"),
])

edit('vite.embed.config.ts', [
    ("    rollupOptions: {\n", "    // M-117 (vite 8): build.rollupOptions became build.rolldownOptions.\n    rolldownOptions: {\n"),
])
