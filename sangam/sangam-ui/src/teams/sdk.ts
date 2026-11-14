// SPDX-License-Identifier: Apache-2.0
/**
 * Decision 162: the only file that imports @microsoft/teams-js 2.59.0 (MIT; installed with --ignore-scripts from the
 * tarballs recorded in DOWNLOADS-WAVE4.md). It is reached only from teams.html, so the console bundle never contains
 * the SDK and removing the Teams module changes nothing else.
 */
import { app, authentication } from '@microsoft/teams-js';

export interface TeamsSdk {
  /** True once the Teams host answered; false outside Teams or when the host does not answer in time. */
  initialize(timeoutMs: number): Promise<boolean>;
  authenticate(url: string): Promise<string>;
  notifySuccess(result: string): void;
  notifyFailure(reason: string): void;
  /** 'default', 'dark' or 'contrast', when the host tells. */
  theme(): Promise<string | undefined>;
}

export const teamsSdk: TeamsSdk = {
  async initialize(timeoutMs) {
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      await Promise.race([
        app.initialize(),
        new Promise<never>((_, reject) => {
          timer = setTimeout(() => reject(new Error('Teams host did not answer')), timeoutMs);
        }),
      ]);
      return true;
    } catch {
      return false;
    } finally {
      if (timer) clearTimeout(timer);
    }
  },
  authenticate: (url) => authentication.authenticate({ url, width: 600, height: 640 }),
  notifySuccess: (result) => authentication.notifySuccess(result),
  notifyFailure: (reason) => authentication.notifyFailure(reason),
  async theme() {
    try {
      return (await app.getContext()).app.theme;
    } catch {
      return undefined;
    }
  },
};
