// SPDX-License-Identifier: Apache-2.0
/** UX-SPEC 6.3 (decision 397): every server answer gets a plain title and a next step. */
import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client';
import en from '../i18n/locales/en.json';
import { problemKind, problemNextKey, problemTitleKey } from './ui';

const err = (status: number, code?: string) => new ApiError({ status, title: 'x', code } as never);
const text = (key: string) => key.split('.').reduce<unknown>((o, k) => (o as Record<string, unknown>)?.[k], en) as string | undefined;

describe('problem titles and next steps', () => {
  it.each([
    [400, undefined, 'validation', 'Some details need fixing'],
    [422, undefined, 'validation', 'Some details need fixing'],
    [401, undefined, 'sessionEnded', 'Your session has ended'],
    [403, undefined, 'notAvailable', 'You do not have access to this'],
    [404, undefined, 'notFound', 'We could not find this'],
    [409, undefined, 'conflict', 'Someone else changed this first'],
    [409, 'stale-lease', 'stale', 'This task was updated while you had it open'],
    [409, 'FORM_VERSION_STALE', 'stale', 'This task was updated while you had it open'],
    [409, 'APPROVAL_INVALIDATED', 'approvalInvalidated', 'The approval no longer applies because the request changed'],
    [409, 'CONFLICT_RETRY_EXHAUSTED', 'retryExhausted', 'The system was busy and could not save'],
    [501, undefined, 'notInstalled', 'This feature is not installed here'],
    [400, 'CAPABILITY_NOT_INSTALLED', 'notInstalled', 'This feature is not installed here'],
    [503, undefined, 'backpressure', 'The system is busy. Your work is saved.'],
    [0, undefined, 'network', 'The server could not be reached'],
    [500, undefined, 'generic', 'Something went wrong'],
  ])('%s %s is %s', (status, code, kind, title) => {
    const e = err(status, code);
    expect(problemKind(e)).toBe(kind);
    expect(text(problemTitleKey(e))).toBe(title);
    expect(text(problemNextKey(e))).toBeTruthy();
  });

  it('a known refusal code keeps its own title', () => {
    expect(problemTitleKey(err(409, 'USER_EXISTS'))).toBe('error.code.USER_EXISTS');
  });

  it('an error that is not from the server is generic', () => {
    expect(problemTitleKey(new Error('boom'))).toBe('error.generic');
    expect(text(problemNextKey(new Error('boom')))).toMatch(/reference number/);
  });
});
