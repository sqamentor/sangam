// SPDX-License-Identifier: Apache-2.0
// Minimal typings for bpmn.io packages that ship no TypeScript declarations.

declare module 'dmn-js/lib/Modeler' {
  export default class DmnModeler {
    constructor(options: { container: HTMLElement; keyboard?: { bindTo?: unknown }; common?: Record<string, unknown> });
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

declare module '*.css';
