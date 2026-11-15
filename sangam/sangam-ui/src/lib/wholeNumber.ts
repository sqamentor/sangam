// SPDX-License-Identifier: Apache-2.0
/**
 * A count typed into a number box and sent as a required int of the contract (JobRetriesRequest.retries,
 * TtlRequest.ttlDays): the text must be a whole number of at least zero. An empty box is not 0 and a fraction is not
 * rounded: the screen refuses both by name (form.required, form.wholeNumber) instead of sending a value nobody typed
 * (Number('') is 0, so an empty TTL box would set a history time-to-live of 0 days).
 */
export type WholeNumberProblem = 'form.required' | 'form.wholeNumber';

/** The i18n key of what is wrong with the text as a whole number of at least zero, or undefined when it is one. */
export function wholeNumberProblem(text: string): WholeNumberProblem | undefined {
  const s = text.trim();
  if (s === '') return 'form.required';
  return /^\d+$/.test(s) && Number.isSafeInteger(Number(s)) ? undefined : 'form.wholeNumber';
}
