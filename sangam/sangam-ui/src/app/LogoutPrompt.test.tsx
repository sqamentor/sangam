// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
// M-001 (owner decision 2026-11-12): the logout prompt for unsent field records, in English and Hindi, with its two
// actions. That the records stay encrypted and bound to their user either way is covered in fieldSync.test.ts.
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import i18n from 'i18next';
import { initI18n } from '../i18n';
import { LogoutPrompt } from './LogoutPrompt';

beforeAll(() => initI18n('en'));
afterEach(cleanup);

describe('M-001 logout prompt', () => {
  it('says how many records are not yet sent and offers Sync now and Log out anyway', () => {
    const onSync = vi.fn();
    const onLogout = vi.fn();
    render(<LogoutPrompt count={3} onSync={onSync} onLogout={onLogout} />);
    expect(screen.getByText('3 records not yet sent')).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Unsent field records' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Sync now' }));
    expect(onSync).toHaveBeenCalledTimes(1);
    expect(onLogout).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Log out anyway' }));
    expect(onLogout).toHaveBeenCalledTimes(1);
  });

  it('is not a modal: no dialog role, nothing else on the page is blocked', () => {
    render(<LogoutPrompt count={1} onSync={() => undefined} onLogout={() => undefined} />);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });

  it('disables both actions while a sync runs', () => {
    render(<LogoutPrompt count={2} busy onSync={() => undefined} onLogout={() => undefined} />);
    for (const b of screen.getAllByRole('button')) expect((b as HTMLButtonElement).disabled).toBe(true);
  });

  it('has the Hindi text', async () => {
    await i18n.changeLanguage('hi');
    render(<LogoutPrompt count={4} onSync={() => undefined} onLogout={() => undefined} />);
    expect(screen.getByText('4 रिकॉर्ड अभी तक नहीं भेजे गए')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'फिर भी लॉग आउट करें' })).toBeTruthy();
    await i18n.changeLanguage('en');
  });
});
