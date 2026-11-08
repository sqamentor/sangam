// SPDX-License-Identifier: Apache-2.0
// Example Sangam UI plugin: one page, one dashboard widget. Plain ES module, no build step, no inline script.
export default {
  contributions: [
    {
      point: 'app.page',
      id: 'hello',
      label: { en: 'Hello plugin', hi: 'नमस्ते प्लगइन' },
      render(el, ctx) {
        const p = document.createElement('p');
        p.textContent = ctx.language === 'hi' ? 'यह पृष्ठ एक सत्यापित प्लगइन से आया है।' : 'This page comes from a verified plugin.';
        el.append(p);
      },
    },
    {
      point: 'dashboard.widget',
      id: 'whoami',
      label: { en: 'Signed in as', hi: 'साइन इन' },
      render(el, ctx) {
        ctx.api.get('/me').then((me) => {
          el.textContent = me.principalId ?? me.userId ?? '';
        });
      },
    },
  ],
};
