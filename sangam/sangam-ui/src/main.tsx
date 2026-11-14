// SPDX-License-Identifier: Apache-2.0
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
// Fonts (owner decisions 120, 121): the approved deploy/fonts Noto TTFs are copied into the build as /fonts/noto/
// (scripts/deploy-fonts.mjs) and declared by applyFonts(), so Devanagari renders offline (air-gapped, no CDN).
import './styles.css';
import { loadConfig } from './config';
import { applyFonts } from './fonts';
import { initI18n } from './i18n';
import { AuthProvider } from './auth/AuthProvider';
import { App } from './app/App';

async function boot() {
  const cfg = await loadConfig();
  await applyFonts(cfg.fonts);
  initI18n(cfg.defaultLanguage);
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <AuthProvider>
        <App />
      </AuthProvider>
    </StrictMode>,
  );
}

void boot();
