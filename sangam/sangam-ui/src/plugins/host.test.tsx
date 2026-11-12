// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * P59 plugin points (TESTING-GAP-REGISTER.md UI-parity rows): a contribution mounts into its slot with the page
 * context, is cleaned up on unmount, and a failing plugin is contained (a notice, the page keeps rendering).
 */
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import i18n from 'i18next';

vi.mock('../api/client', async (orig) => {
  const real = await orig<typeof import('../api/client')>();
  return { ...real, http: { ...real.http, get: vi.fn(async () => []) } };
});

import { initI18n } from '../i18n';
import { POINTS, PluginMount, PluginSlot } from './host';

beforeAll(() => initI18n('en'));
afterEach(cleanup);

describe('plugin points (P59)', () => {
  it('a contribution renders into its element with the page context and is cleaned up', () => {
    const clean = vi.fn();
    const render1 = vi.fn((el: HTMLElement, ctx: { processInstanceId?: string; language: string }) => {
      el.textContent = `instance ${ctx.processInstanceId} (${ctx.language})`;
      return clean;
    });
    const { unmount } = render(<PluginMount item={{ pluginId: 'p1', contribution: { point: 'process-instance.tab', id: 't', label: { en: 'T', hi: 'T' }, render: render1 } }} ctx={{ processInstanceId: 'pi1' }} />);
    expect(screen.getByText('instance pi1 (en)')).toBeTruthy();
    unmount();
    expect(clean).toHaveBeenCalled();
  });

  it('a failing plugin shows a notice instead of breaking the page', () => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    render(<PluginMount item={{ pluginId: 'bad', contribution: { point: 'dashboard.widget', id: 'w', label: { en: 'W', hi: 'W' }, render: () => { throw new Error('boom'); } } }} ctx={{}} />);
    expect(screen.getByText(i18n.t('plugin.failed', { id: 'bad' }) as string)).toBeTruthy();
    err.mockRestore();
  });

  it('a slot with no verified plugins renders nothing, and the welcome dashboard point exists', async () => {
    const { container } = render(<PluginSlot point="dashboard.widget" />);
    await Promise.resolve();
    expect(container.innerHTML).toBe('');
    expect(POINTS).toContain('dashboard.widget');
  });
});
