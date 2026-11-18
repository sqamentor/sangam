// SPDX-License-Identifier: Apache-2.0
/**
 * Model editors on bpmn.io (R69, EC-04, PF-21/36): BPMN (bpmn-js Modeler), DMN (dmn-js Modeler; hit policies
 * PRIORITY / OUTPUT ORDER are editable and executed by the FC-54 handlers) and forms (form-js editor).
 * LIC-8 / PK-11: the bpmn.io watermark ("powered by bpmn.io") is rendered by each library and is never hidden.
 * CMMN uses Sangam's own diagram-js editor (decision 161) with an XML fallback.
 * Each editor exposes getContent() through the onReady callback so the package workspace can save.
 */
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import BpmnModeler from 'bpmn-js/lib/Modeler';
import DmnModeler from 'dmn-js/lib/Modeler';
import { FormEditor } from '@bpmn-io/form-js-editor';
import 'bpmn-js/dist/assets/diagram-js.css';
import 'bpmn-js/dist/assets/bpmn-js.css';
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn-embedded.css';
import 'dmn-js/dist/assets/diagram-js.css';
import 'dmn-js/dist/assets/dmn-js-shared.css';
import 'dmn-js/dist/assets/dmn-js-drd.css';
import 'dmn-js/dist/assets/dmn-js-decision-table.css';
import 'dmn-js/dist/assets/dmn-js-decision-table-controls.css';
import 'dmn-js/dist/assets/dmn-js-literal-expression.css';
import 'dmn-js/dist/assets/dmn-js-boxed-expression.css';
import 'dmn-js/dist/assets/dmn-js-boxed-expression-controls.css';
import 'dmn-js/dist/assets/dmn-font/css/dmn-embedded.css';
import '@bpmn-io/form-js-editor/dist/assets/form-js-editor.css';
import '@bpmn-io/form-js-viewer/dist/assets/form-js.css';
import { ProblemAlert } from './ui';
import { ElementTemplatePanel, type ModelerLike, type Selected } from './ElementTemplatePanel';
import { TemplatePalette } from './TemplatePalette';
import { FormComponentPanel, type FormEditorLike } from './FormComponentPanel';
import { CmmnParseError, parseCmmn } from './cmmn/cmmnModel';
import { CmmnDiagramEditor, type CmmnDiagramHandle } from './cmmn/CmmnDiagramEditor';

export interface EditorHandle {
  getContent: () => Promise<string>;
}

export const EMPTY_BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" id="Definitions_1" targetNamespace="http://sangam.org/bpmn">
  <bpmn:process id="Process_1" isExecutable="true">
    <bpmn:startEvent id="StartEvent_1" />
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="Process_1">
      <bpmndi:BPMNShape id="StartEvent_1_di" bpmnElement="StartEvent_1"><dc:Bounds x="180" y="160" width="36" height="36" /></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`;

export const EMPTY_DMN = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" xmlns:dmndi="https://www.omg.org/spec/DMN/20191111/DMNDI/" xmlns:dc="http://www.omg.org/spec/DMN/20180521/DC/" id="Definitions_1" name="Decisions" namespace="http://sangam.org/dmn">
  <decision id="Decision_1" name="Decision 1">
    <decisionTable id="DecisionTable_1" hitPolicy="FIRST">
      <input id="Input_1" label="amount"><inputExpression id="InputExpression_1" typeRef="number"><text>amount</text></inputExpression></input>
      <output id="Output_1" label="seat" name="seat" typeRef="string" />
    </decisionTable>
  </decision>
  <dmndi:DMNDI><dmndi:DMNDiagram id="DMNDiagram_1"><dmndi:DMNShape id="DMNShape_1" dmnElementRef="Decision_1"><dc:Bounds height="80" width="180" x="160" y="100" /></dmndi:DMNShape></dmndi:DMNDiagram></dmndi:DMNDI>
</definitions>`;

export const EMPTY_FORM = JSON.stringify({ type: 'default', schemaVersion: 16, components: [] }, null, 2);

/** packageId: the package being edited, for the M-068 operation picker of the template fields (none outside a package). */
export function BpmnEditor({ xml, onReady, onChange, height = 560, packageId }: { xml: string; onReady: (h: EditorHandle) => void; onChange?: () => void; height?: number; packageId?: string }) {
  const { t } = useTranslation();
  const host = useRef<HTMLDivElement>(null);
  const [err, setErr] = useState<Error>();
  const changeRef = useRef(onChange);
  changeRef.current = onChange;
  // M-036: the selected element, for the element-template panel
  const [modeler, setModeler] = useState<ModelerLike | null>(null);
  const [selected, setSelected] = useState<Selected | null>(null);
  useEffect(() => {
    if (!host.current) return;
    const m = new BpmnModeler({ container: host.current }); // bpmn-js 18: the keyboard binds to the focusable canvas itself
    setModeler(m as unknown as ModelerLike);
    setSelected(null);
    (m.get('eventBus') as { on: (e: string, cb: (ev: { newSelection?: { id: string; businessObject?: { $type?: string } }[] }) => void) => void }).on('selection.changed', (ev) => {
      const el = ev.newSelection?.length === 1 ? ev.newSelection[0] : undefined;
      setSelected(el?.businessObject?.$type ? { id: el.id, type: el.businessObject.$type } : null);
    });
    m.importXML(xml || EMPTY_BPMN)
      .then(() => {
        (m.get('canvas') as { zoom: (z: string) => void }).zoom('fit-viewport');
        onReady({ getContent: async () => (await m.saveXML({ format: true })).xml ?? '' });
      })
      .catch((e: Error) => setErr(e));
    (m.get('eventBus') as { on: (e: string, cb: () => void) => void }).on('commandStack.changed', () => changeRef.current?.());
    return () => m.destroy();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [xml]);
  return (
    <div className="editor">
      <ProblemAlert error={err} />
      <div ref={host} className="bpmn-host" style={{ height }} tabIndex={0} aria-label={t('modeller.bpmnCanvas')} />
      <p className="hint">{t('modeller.editorKeyboardHint')}</p>
      <TemplatePalette modeler={modeler} packageId={packageId} />
      <ElementTemplatePanel modeler={modeler} selected={selected} packageId={packageId} />
    </div>
  );
}

export function DmnEditor({ xml, onReady, onChange, height = 520 }: { xml: string; onReady: (h: EditorHandle) => void; onChange?: () => void; height?: number }) {
  const { t } = useTranslation();
  const host = useRef<HTMLDivElement>(null);
  const [err, setErr] = useState<Error>();
  const [views, setViews] = useState<{ type: string; element: { id: string; name?: string } }[]>([]);
  const ref = useRef<DmnModeler | null>(null);
  const changeRef = useRef(onChange);
  changeRef.current = onChange;
  useEffect(() => {
    if (!host.current) return;
    const m = new DmnModeler({ container: host.current }); // dmn-js 18: the keyboard binds to the focusable canvas itself
    ref.current = m;
    m.on('views.changed', () => setViews(m.getViews()));
    m.importXML(xml || EMPTY_DMN)
      .then(() => {
        setViews(m.getViews());
        onReady({ getContent: async () => (await m.saveXML({ format: true })).xml });
      })
      .catch((e: Error) => setErr(e));
    m.on('elements.changed', () => changeRef.current?.());
    return () => m.destroy();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [xml]);
  return (
    <div className="editor">
      <ProblemAlert error={err} />
      {views.length > 1 && (
        <div className="toolbar" role="group" aria-label={t('dmn.views')}>
          {views.map((v) => (
            <button key={v.element.id} type="button" onClick={() => void ref.current?.open(v)}>
              {v.element.name ?? v.element.id} ({v.type})
            </button>
          ))}
        </div>
      )}
      <div ref={host} className="dmn-host" style={{ height }} tabIndex={0} aria-label={t('modeller.dmnCanvas')} />
    </div>
  );
}

export function FormBuilder({ schema, onReady, onChange, height = 560 }: { schema: string; onReady: (h: EditorHandle) => void; onChange?: () => void; height?: number }) {
  const { t } = useTranslation();
  const host = useRef<HTMLDivElement>(null);
  const [err, setErr] = useState<Error>();
  const changeRef = useRef(onChange);
  changeRef.current = onChange;
  // C3-14: the editor, for the form-component templates panel
  const [editor, setEditor] = useState<FormEditorLike | null>(null);
  useEffect(() => {
    if (!host.current) return;
    const ed = new FormEditor({ container: host.current });
    setEditor(ed as unknown as FormEditorLike);
    let parsed: unknown;
    try {
      parsed = JSON.parse(schema || EMPTY_FORM);
    } catch {
      parsed = JSON.parse(EMPTY_FORM);
    }
    ed.importSchema(parsed as never)
      .then(() => onReady({ getContent: async () => JSON.stringify(ed.saveSchema(), null, 2) }))
      .catch((e: Error) => setErr(e));
    ed.on('changed', () => changeRef.current?.());
    return () => ed.destroy();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [schema]);
  return (
    <div className="editor">
      <ProblemAlert error={err} />
      <div ref={host} className="form-host" style={{ minHeight: height }} aria-label={t('modeller.formCanvas')} />
      <p className="hint">{t('modeller.formI18nHint')}</p>
      <FormComponentPanel editor={editor} onInserted={() => changeRef.current?.()} />
    </div>
  );
}

/**
 * CMMN (owner decision 161, M-041). There is no maintained bpmn.io CMMN editor (EC-04, D-43), so Sangam has its own
 * CMMN 1.1 diagram editor on diagram-js (src/components/cmmn). The XML source view is the fallback: it opens when the
 * diagram cannot read the file, and it can always be chosen. Switching views carries the edits across.
 */
export function CmmnEditor({ xml, onReady, onChange }: { xml: string; onReady: (h: EditorHandle) => void; onChange?: () => void }) {
  const { t } = useTranslation();
  const readable = (text: string): CmmnParseError | null => {
    try {
      parseCmmn(text);
      return null;
    } catch (e) {
      return e instanceof CmmnParseError ? e : new CmmnParseError('xml');
    }
  };
  const [view, setView] = useState<'diagram' | 'xml'>(() => (readable(xml) ? 'xml' : 'diagram'));
  /** The text the diagram opened from (diagram view) or the text being edited (XML view). */
  const [text, setText] = useState(xml);
  const [problem, setProblem] = useState<CmmnParseError | null>(() => readable(xml));
  const textRef = useRef(text);
  textRef.current = text;
  const viewRef = useRef(view);
  viewRef.current = view;
  /**
   * The mounted diagram view's handle. The diagram view sets it when it is up and clears it when it is taken down
   * (CmmnDiagramEditor onReady). It is not cleared in the effect below: a child's effect runs before its parent's, so
   * that cleared the handle the diagram had just registered, and a save from the diagram view returned the text the
   * diagram opened from, without the diagram's edits.
   */
  const diagram = useRef<CmmnDiagramHandle | null>(null);
  const current = () => (viewRef.current === 'diagram' && diagram.current ? diagram.current.getXml() : textRef.current);
  useEffect(() => {
    const p = readable(xml);
    setText(xml);
    setProblem(p);
    setView(p ? 'xml' : 'diagram');
    onReady({ getContent: async () => current() });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [xml]);
  const show = (next: 'diagram' | 'xml') => {
    if (next === view) return;
    if (next === 'xml') {
      setText(current());
      diagram.current = null;
      setView('xml');
      return;
    }
    const p = readable(textRef.current);
    setProblem(p);
    if (!p) setView('diagram');
  };
  return (
    <div className="editor">
      <div role="tablist" aria-label={t('modeller.cmmnViews')} className="row">
        <button type="button" role="tab" aria-selected={view === 'diagram'} className="btn btn-sm" onClick={() => show('diagram')}>
          {t('modeller.cmmnViewDiagram')}
        </button>
        <button type="button" role="tab" aria-selected={view === 'xml'} className="btn btn-sm" onClick={() => show('xml')}>
          {t('modeller.cmmnViewXml')}
        </button>
      </div>
      {problem && (
        <p className="field-error" role="alert">
          {t('modeller.cmmnDiagramUnavailable', { reason: t(`modeller.cmmnReason.${problem.code}`) })}
        </p>
      )}
      {view === 'diagram' ? (
        <CmmnDiagramEditor key={text} xml={text} onChange={onChange} onReady={(h) => (diagram.current = h)} />
      ) : (
        <CmmnSourceEditor
          text={text}
          onText={(v) => {
            setText(v);
            onChange?.();
          }}
        />
      )}
    </div>
  );
}

/** The XML view of the CMMN editor: the source with a plan-item outline. */
export function CmmnSourceEditor({ text, onText }: { text: string; onText: (text: string) => void }) {
  const { t } = useTranslation();
  const outline = (() => {
    try {
      const doc = new DOMParser().parseFromString(text, 'application/xml');
      if (doc.getElementsByTagName('parsererror').length) return { error: true, items: [] as { tag: string; id: string; name: string }[] };
      const tags = ['casePlanModel', 'stage', 'humanTask', 'processTask', 'caseTask', 'decisionTask', 'milestone', 'timerEventListener', 'userEventListener', 'sentry'];
      const items: { tag: string; id: string; name: string }[] = [];
      for (const tg of tags) {
        const els = doc.getElementsByTagNameNS('*', tg);
        for (let i = 0; i < els.length; i++) items.push({ tag: tg, id: els[i].getAttribute('id') ?? '', name: els[i].getAttribute('name') ?? '' });
      }
      return { error: false, items };
    } catch {
      return { error: true, items: [] };
    }
  })();
  return (
    <div className="grid-2">
      <div className="field">
        <label htmlFor="cmmn-src">{t('modeller.cmmnSource')}</label>
        <textarea id="cmmn-src" className="code" rows={28} spellCheck={false} value={text} onChange={(e) => onText(e.target.value)} />
      </div>
      <div>
        <h3>{t('modeller.cmmnOutline')}</h3>
        {outline.error && <p className="field-error">{t('modeller.xmlInvalid')}</p>}
        <ul className="plan-tree">
          {outline.items.map((i) => (
            <li key={`${i.tag}:${i.id}`}>
              <span className="muted small">{i.tag}</span> {i.name || i.id}
              {(i.tag === 'timerEventListener' || i.tag === 'userEventListener') && <span className="badge badge-warn">{t('modeller.cmmnListenerRejected')}</span>}
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
