// SPDX-License-Identifier: Apache-2.0
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
// OSI fallback fonts are bundled from npm (@fontsource, SIL OFL 1.1) so Devanagari renders offline (air-gapped, no CDN).
// The central-government font (C-DAC Sakal Bharati, OFL 1.1) is served from public/fonts/gov/ and declared by applyFonts().
import '@fontsource/noto-sans/400.css';
import '@fontsource/noto-sans/600.css';
import '@fontsource/noto-sans/700.css';
import '@fontsource/noto-sans-devanagari/400.css';
import '@fontsource/noto-sans-devanagari/600.css';
import '@fontsource/noto-sans-devanagari/700.css';
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
