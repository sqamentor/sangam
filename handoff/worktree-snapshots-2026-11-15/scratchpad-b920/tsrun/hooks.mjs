// local-only: resolve "./x.js" to "./x.ts" when only the .ts source exists (no tsc available offline)
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
export async function resolve(specifier, context, next) {
  if (specifier.endsWith('.js') && (specifier.startsWith('.') || specifier.startsWith('/')) && context.parentURL) {
    const ts = new URL(specifier.slice(0, -3) + '.ts', context.parentURL);
    if (existsSync(fileURLToPath(ts))) return next(ts.href, context);
  }
  return next(specifier, context);
}
