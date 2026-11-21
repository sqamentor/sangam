// SPDX-License-Identifier: Apache-2.0
// Minimal typings for bpmn.io packages that ship no TypeScript declarations.

declare module 'dmn-js/lib/Modeler' {
  export default class DmnModeler {
    // dmn-js 18 (diagram-js 15) removed keyboard.bindTo: the canvas binds its own keyboard
    constructor(options: { container: HTMLElement; common?: Record<string, unknown> });
    importXML(xml: string): Promise<{ warnings: unknown[] }>;
    saveXML(options?: { format?: boolean }): Promise<{ xml: string }>;
    getActiveViewer(): { get: <T = unknown>(name: string) => T } | undefined;
    getViews(): { type: string; element: { id: string; name?: string } }[];
    open(view: unknown): Promise<unknown>;
    on(event: string, cb: (e: unknown) => void): void;
    destroy(): void;
  }
}

declare module 'dmn-js/lib/Viewer' {
  export default class DmnViewer {
    constructor(options: { container: HTMLElement });
    importXML(xml: string): Promise<{ warnings: unknown[] }>;
    getViews(): { type: string; element: { id: string; name?: string } }[];
    open(view: unknown): Promise<unknown>;
    destroy(): void;
  }
}

declare module 'bpmn-moddle' {
  // The serialisation layer of bpmn-js (Viewer.importXML / saveXML); used directly by the MIWG round-trip test.
  export class BpmnModdle {
    constructor(packages?: Record<string, unknown>);
    fromXML(xml: string, typeName?: string): Promise<{ rootElement: unknown; warnings: { message: string }[] }>;
    toXML(element: unknown, options?: { format?: boolean }): Promise<{ xml: string }>;
  }
}

declare module '*.css';
