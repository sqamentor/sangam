// SPDX-License-Identifier: Apache-2.0
// Shared test setup (vite.config.ts test.setupFiles), loaded before every test file in every environment.
//
// jsdom 25 has HTMLDialogElement but neither showModal() nor close(). The shared <Dialog> (components/ui.tsx) calls
// both, so every test that opens a dialog threw "d.showModal is not a function" unless its own file patched the
// prototype. The patch lives here once; it only fills what is missing, so a jsdom that gains the methods wins.
if (typeof HTMLDialogElement !== 'undefined') {
  const proto = HTMLDialogElement.prototype as unknown as { showModal?: () => void; show?: () => void; close?: () => void };
  const open = function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
  proto.showModal ??= open;
  proto.show ??= open;
  proto.close ??= function (this: HTMLDialogElement) {
    this.removeAttribute('open');
  };
}
