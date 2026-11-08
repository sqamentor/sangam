// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * P02 (TESTING-GAP-REGISTER.md UI-parity rows): the diagram turns token counts, incidents and extra badges (job
 * definitions, called processes, plugins) into bpmn-js overlays on the right elements. bpmn-js cannot lay out SVG in
 * jsdom, so the viewer is replaced by a recorder with the same overlay / registry / canvas API.
 */
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';

const rec = vi.hoisted(() => ({
  added: [] as { id: string; type: string; html: HTMLElement }[],
  markers: [] as string[],
  shapes: new Set(['svc', 'call', 'review']),
}));

vi.mock('bpmn-js/lib/NavigatedViewer', () => ({
  default: class {
    get(name: string) {
      if (name === 'eventBus') return { on: () => undefined, off: () => undefined };
      if (name === 'canvas') return { zoom: () => undefined, addMarker: (id: string, m: string) => rec.markers.push(`${id}:${m}`), removeMarker: () => undefined };
      if (name === 'elementRegistry') return { get: (id: string) => (rec.shapes.has(id) ? { id, width: 100, height: 80 } : undefined), getAll: () => [...rec.shapes].map((id) => ({ id })) };
      if (name === 'overlays') return { add: (id: string, type: string, o: { html: HTMLElement }) => rec.added.push({ id, type, html: o.html }), clear: () => (rec.added.length = 0) };
      throw new Error(name);
    }
    importXML() {
      return Promise.resolve({ warnings: [] });
    }
    destroy() {}
  },
}));

import { initI18n } from '../i18n';
import { BpmnDiagram } from './BpmnDiagram';

beforeAll(() => initI18n('en'));
afterEach(cleanup);

describe('BpmnDiagram overlays (P02)', () => {
  it('badges become overlays on existing elements only; a badge with a link is an anchor', async () => {
    render(
      <BpmnDiagram
        title="Leave"
        xml="<xml/>"
        stats={[{ activityId: 'review', count: 3, incidents: 1 } as never]}
        badges={[{ activityId: 'svc', text: '⚙ async-continuation' }, { activityId: 'call', text: '→ sub', href: '/ops/processes/sub1' }, { activityId: 'gone', text: 'x' }]}
      />,
    );
    await waitFor(() => expect(rec.added.filter((a) => a.type === 'sg-extra')).toHaveLength(2));
    const extra = rec.added.filter((a) => a.type === 'sg-extra');
    expect(extra.map((a) => [a.id, a.html.tagName, a.html.textContent])).toEqual([['svc', 'SPAN', '⚙ async-continuation'], ['call', 'A', '→ sub']]);
    expect((extra[1].html as HTMLAnchorElement).getAttribute('href')).toBe('/ops/processes/sub1');
    expect(rec.added.find((a) => a.type === 'sg-count')?.html.textContent).toBe('3');
    expect(rec.added.find((a) => a.type === 'sg-inc')?.id).toBe('review');
    expect(rec.markers).toContain('review:sg-incident');
  });

  it('a compact thumbnail has no keyboard hint and no data table', async () => {
    const { container } = render(<BpmnDiagram compact title="Leave" xml="<xml/>" height={160} stats={[{ activityId: 'review', count: 1, incidents: 0 } as never]} />);
    await waitFor(() => expect(container.querySelector('.bpmn-host')).toBeTruthy());
    expect((container.querySelector('.bpmn-host') as HTMLElement).style.height).toBe('160px');
    expect(container.querySelector('details')).toBeNull();
    expect(container.querySelector('.hint')).toBeNull();
  });
});
