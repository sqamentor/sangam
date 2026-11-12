// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * M-049 (P2-027(c), LIC-8, SANGAM.md X.3): the bpmn.io watermark stays visible. The bpmn-js licence requires the
 * "Powered by bpmn.io" logo that BaseViewer adds to every viewer's container (bpmn-js/lib/BaseViewer.js addProjectLogo,
 * class bjs-powered-by). This file uses the REAL bpmn-js viewer (BpmnDiagram.test.tsx replaces it with a recorder), so
 * removing the logo, hiding it inline or with an app style rule fails here. The CI grep in ui.yml only proves the string
 * is in dist/.
 *
 * jsdom has no SVG layout, so the few SVG geometry calls diagram-js makes are stubbed; importing the XML may still fail
 * in jsdom, which BpmnDiagram reports as a problem, and the logo is added before that, when the viewer is constructed.
 */
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';
import appCss from '../styles.css?raw';
import { initI18n } from '../i18n';
import { BpmnDiagram } from './BpmnDiagram';

function stubSvgGeometry() {
  const matrix = () => {
    const m = { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0, multiply: () => m, inverse: () => m, translate: () => m, scale: () => m, rotate: () => m };
    return m;
  };
  const proto = (globalThis as unknown as { SVGElement: { prototype: Record<string, unknown> } }).SVGElement.prototype;
  proto.getBBox ??= () => ({ x: 0, y: 0, width: 100, height: 80 });
  proto.getScreenCTM ??= () => matrix();
  proto.getCTM ??= () => matrix();
  proto.createSVGMatrix ??= () => matrix();
  proto.createSVGTransform ??= () => ({ matrix: matrix(), setMatrix: () => undefined, setTranslate: () => undefined, setScale: () => undefined });
  proto.createSVGTransformFromMatrix ??= (m: unknown) => ({ matrix: m, setMatrix: () => undefined });
}

beforeAll(() => {
  stubSvgGeometry();
  initI18n('en');
});
afterEach(cleanup);

const XML = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="d" targetNamespace="t"><bpmn:process id="p" /></bpmn:definitions>`;

/** CSS rules in the app's own stylesheet whose selector names the watermark. */
function rulesFor(css: string, cls: string): string[] {
  const noComments = css.replace(/\/\*[\s\S]*?\*\//g, '');
  return [...noComments.matchAll(/([^{}]+)\{([^}]*)\}/g)].filter((m) => m[1].includes(cls)).map((m) => `${m[1].trim()} {${m[2]}}`);
}

describe('M-049 bpmn.io watermark', () => {
  it('is rendered by the real viewer inside the diagram host and is not hidden inline', async () => {
    const { container } = render(<BpmnDiagram title="Leave" xml={XML} />);
    const logo = await waitFor(() => {
      const a = container.querySelector('.bpmn-host .bjs-powered-by') as HTMLAnchorElement | null;
      expect(a).toBeTruthy();
      return a!;
    });
    expect(logo.getAttribute('href')).toMatch(/bpmn\.io/);
    expect(logo.getAttribute('title') ?? '').toMatch(/bpmn\.io/i);
    expect(logo.style.display).not.toBe('none');
    expect(logo.style.visibility).not.toBe('hidden');
    expect(logo.style.opacity === '' || Number(logo.style.opacity) > 0).toBe(true);
    expect(logo.hidden).toBe(false);
    expect(logo.getAttribute('aria-hidden')).not.toBe('true');
    // nothing on the way up to the host hides it either
    for (let el: HTMLElement | null = logo; el && el !== container; el = el.parentElement) {
      expect(el.style.display, el.className).not.toBe('none');
      expect(el.style.visibility, el.className).not.toBe('hidden');
    }
  });

  it('is never hidden, covered or shrunk by the app stylesheet', () => {
    for (const rule of rulesFor(appCss, 'bjs-powered-by')) {
      expect(rule).not.toMatch(/display\s*:\s*none|visibility\s*:\s*hidden|opacity\s*:\s*0(\.0*)?\s*(;|}|$)|width\s*:\s*0|height\s*:\s*0|clip|transform\s*:\s*scale\(0/);
    }
    // the host keeps the logo's absolute position inside the canvas (position: relative on .bpmn-host)
    expect(rulesFor(appCss, '.bpmn-host').join('\n')).toMatch(/position\s*:\s*relative/);
  });
});
