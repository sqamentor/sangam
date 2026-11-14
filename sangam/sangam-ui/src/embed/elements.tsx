// SPDX-License-Identifier: Apache-2.0
/**
 * M-043: the custom elements <sangam-task-form> and <sangam-process-diagram>.
 *
 * Each element renders into its own open shadow root, so the host page's CSS never reaches the Sangam view and the
 * Sangam CSS never leaks into the host page. The Sangam style sheet (styles.css, whose tokens are declared for :root and
 * :host) and, for the diagram, the bpmn-js style sheets are placed inside the shadow root. Fonts are inherited from the
 * host page (an @font-face inside a shadow root is not applied by browsers).
 *
 * Attributes are plain ids and options. The access token is never an attribute: it comes from the host through
 * configure({ getAccessToken }) on every request (src/embed/index.ts).
 *
 * Events (bubbling and composed, so they cross the shadow boundary):
 * - sangam-task-completed  detail { taskId, result }   the task was completed
 * - sangam-error           detail { taskId?, definitionId?, status, code?, title }   a request failed
 * - sangam-element-selected detail { definitionId, activityId, name? }   a diagram element was chosen
 */
import { StrictMode, type ReactElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import i18n from 'i18next';
import appCss from '../styles.css?inline';
import diagramJsCss from 'bpmn-js/dist/assets/diagram-js.css?inline';
import bpmnJsCss from 'bpmn-js/dist/assets/bpmn-js.css?inline';
import { ApiError } from '../api/client';
import type { OverlayMode } from '../components/BpmnDiagram';
import { Notice } from '../components/ui';
import { EmbeddedProcessDiagram, EmbeddedTaskForm } from './EmbeddedViews';

const HOST_CSS = ':host{display:block;font-family:var(--font);color:var(--text);line-height:1.55}:host([hidden]){display:none}';

export const TASK_FORM_TAG = 'sangam-task-form';
export const PROCESS_DIAGRAM_TAG = 'sangam-process-diagram';

let isConfigured = (): boolean => false;
/** Set by configure(): the elements show a notice instead of calling the API until the host has configured Sangam. */
export function setConfiguredCheck(check: () => boolean): void {
  isConfigured = check;
}

export function errorDetail(e: Error): { status: number; code?: string; title: string } {
  if (e instanceof ApiError) return { status: e.problem.status, code: e.problem.code, title: e.problem.title };
  return { status: 0, title: e.message };
}

abstract class SangamElement extends HTMLElement {
  private root: Root | undefined;
  private mount: HTMLDivElement | undefined;

  protected abstract readonly extraCss: string;
  protected abstract view(): ReactElement;

  connectedCallback(): void {
    if (!this.mount) {
      const shadow = this.shadowRoot ?? this.attachShadow({ mode: 'open' });
      const style = document.createElement('style');
      style.textContent = `${appCss}\n${this.extraCss}\n${HOST_CSS}`;
      this.mount = document.createElement('div');
      this.mount.className = 'sangam-embed';
      shadow.append(style, this.mount);
    }
    this.root ??= createRoot(this.mount);
    this.update();
  }

  disconnectedCallback(): void {
    const r = this.root;
    this.root = undefined;
    r?.unmount();
  }

  attributeChangedCallback(): void {
    this.update();
  }

  /** Re-renders with the current attributes; called again by configure() when the language or API changes. */
  update(): void {
    if (!this.root) return;
    const lang = this.getAttribute('lang') || i18n.language || 'en';
    this.root.render(
      <StrictMode>
        <div lang={lang}>{isConfigured() ? this.view() : <Notice tone="warn">{i18n.t('embed.notConfigured')}</Notice>}</div>
      </StrictMode>,
    );
  }

  protected emit(type: string, detail: unknown): void {
    this.dispatchEvent(new CustomEvent(type, { detail, bubbles: true, composed: true }));
  }

  protected missing(name: string): ReactElement {
    return <Notice tone="bad">{i18n.t('embed.missingAttribute', { name })}</Notice>;
  }
}

/** <sangam-task-form task-id="…">: the task's form, completed with a plain COMPLETE when the task offers it. */
export class SangamTaskFormElement extends SangamElement {
  static get observedAttributes(): string[] {
    return ['task-id', 'lang'];
  }
  protected readonly extraCss = '';
  protected view(): ReactElement {
    const taskId = this.getAttribute('task-id');
    if (!taskId) return this.missing('task-id');
    return (
      <EmbeddedTaskForm
        key={taskId}
        taskId={taskId}
        callbacks={{
          onCompleted: (result) => this.emit('sangam-task-completed', { taskId, result }),
          onError: (e) => this.emit('sangam-error', { taskId, ...errorDetail(e) }),
        }}
      />
    );
  }
}

const MODES: OverlayMode[] = ['tokens', 'heat', 'duration'];

/**
 * <sangam-process-diagram process-definition-id="…" [process-instance-id="…"] [overlay="tokens|heat|duration"]
 * [height="460"] [label="…"]>: the BPMN diagram, with the instance's tokens when an instance is named.
 */
export class SangamProcessDiagramElement extends SangamElement {
  static get observedAttributes(): string[] {
    return ['process-definition-id', 'process-instance-id', 'overlay', 'height', 'label', 'lang'];
  }
  protected readonly extraCss = `${diagramJsCss}\n${bpmnJsCss}`;
  protected view(): ReactElement {
    const definitionId = this.getAttribute('process-definition-id');
    if (!definitionId) return this.missing('process-definition-id');
    const overlay = this.getAttribute('overlay') as OverlayMode | null;
    const h = Number(this.getAttribute('height'));
    return (
      <EmbeddedProcessDiagram
        definitionId={definitionId}
        instanceId={this.getAttribute('process-instance-id') || undefined}
        mode={overlay && MODES.includes(overlay) ? overlay : 'tokens'}
        height={Number.isFinite(h) && h >= 120 && h <= 4000 ? h : 460}
        title={this.getAttribute('label') || i18n.t('embed.diagramTitle')}
        onSelect={(activityId, name) => this.emit('sangam-element-selected', { definitionId, activityId, name })}
      />
    );
  }
}

/** Registers both elements once; a name another script already registered is left alone. */
export function defineSangamElements(registry: CustomElementRegistry = customElements): void {
  if (!registry.get(TASK_FORM_TAG)) registry.define(TASK_FORM_TAG, SangamTaskFormElement);
  if (!registry.get(PROCESS_DIAGRAM_TAG)) registry.define(PROCESS_DIAGRAM_TAG, SangamProcessDiagramElement);
}

/** Re-renders every connected Sangam element (after configure() changed the language or the API). */
export function refreshSangamElements(doc: Document = document): void {
  const all = doc.querySelectorAll<SangamElement>(`${TASK_FORM_TAG}, ${PROCESS_DIAGRAM_TAG}`);
  all.forEach((el) => el.update());
}
