import sys, os
sys.path.insert(0, sys.argv[1])
from ed import edit
edit('README.md', [('- Vite 8 no longer uses Babel, esbuild, Rollup or browserslist, so they are gone from the tree, and so is `caniuse-lite` (CC-BY-4.0).',
  '- Vite 8 and @vitejs/plugin-react 6 no longer use the Babel compiler (`@babel/core` and its plugins), esbuild, Rollup or browserslist, so they are gone from the tree, and so is `caniuse-lite` (CC-BY-4.0). `@babel/code-frame` and `@babel/runtime` remain as dependencies of other packages.')])
