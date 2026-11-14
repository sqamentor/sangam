set -euo pipefail
npm ci --ignore-scripts --no-audit --no-fund
npm run build
mkdir -p ../../../packages/npm
npm pack --pack-destination ../../../packages/npm
