// SPDX-License-Identifier: Apache-2.0
/**
 * M-046: the axe-core check shared by component tests (axe-core 4.14.0, MPL-2.0, dev dependency only; never shipped).
 *
 * Rules: WCAG 2.0 / 2.1 levels A and AA, the GIGW 3.0 / IS 17802 target (GIGW-CONFORMANCE.md). A test fails on any
 * violation of impact "serious" or "critical"; "moderate" and "minor" ones are returned for the report.
 * color-contrast is off here because jsdom computes no layout or colours; it is checked in the E2E run (M-048) and by the
 * auditor (GIGW-CONFORMANCE.md 1.4.3).
 */
import axe from 'axe-core';

export const AXE_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

export interface A11yIssue {
  id: string;
  impact: string;
  help: string;
  targets: string[];
}

let running: Promise<unknown> = Promise.resolve();

/** Runs axe on a rendered node; calls are serialised (axe allows one run at a time). */
export async function a11yIssues(node: Element): Promise<{ blocking: A11yIssue[]; other: A11yIssue[] }> {
  const run = running.then(() =>
    axe.run(node, { runOnly: { type: 'tag', values: AXE_TAGS }, rules: { 'color-contrast': { enabled: false } }, resultTypes: ['violations'] }),
  );
  running = run.catch(() => undefined);
  const r = await run;
  const all = r.violations.map((v) => ({ id: v.id, impact: v.impact ?? 'minor', help: v.help, targets: v.nodes.map((n) => n.target.join(' ')) }));
  return { blocking: all.filter((v) => v.impact === 'serious' || v.impact === 'critical'), other: all.filter((v) => v.impact !== 'serious' && v.impact !== 'critical') };
}

/** The assertion form: the list of blocking issues, empty when the node passes. */
export async function blockingA11yIssues(node: Element): Promise<string[]> {
  return (await a11yIssues(node)).blocking.map((v) => `${v.id} (${v.impact}): ${v.help} at ${v.targets.join(', ')}`);
}
