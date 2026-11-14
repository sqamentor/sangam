// SPDX-License-Identifier: Apache-2.0
/**
 * Owner decision 161 (M-041): the diagram view of Sangam's CMMN 1.1 editor. The canvas is diagram-js; every action is
 * also a native button or field (toolbar, name field, outline), so the editor works from the keyboard and with a
 * screen reader. The XML view in CmmnEditor is the fallback for anything the diagram does not cover.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import 'diagram-js/assets/diagram-js.css';
import { CREATABLE_KINDS, ENGINE_REJECTED_KINDS, isCriterion, parseCmmn, serializeCmmn, type CmmnNode, type CriterionKind, type ParsedCmmn } from './cmmnModel';
import { canHostCriterion, createCmmnModeler, type CmmnModelerApi } from './CmmnModeler';

export interface CmmnDiagramHandle {
  /** The file text with the diagram's edits applied. */
  getXml: () => string;
}

/** Every id in the parsed file, so new ids never clash with a sentry, an on-part or a definition. */
function fileIds(parsed: ParsedCmmn): Set<string> {
  const ids = new Set<string>();
  const all = parsed.doc.getElementsByTagName('*');
  for (let i = 0; i < all.length; i++) {
    const id = all[i].getAttribute('id');
    if (id) ids.add(id);
  }
  return ids;
}

export function CmmnDiagramEditor({ xml, onChange, onReady, height = 560 }: { xml: string; onChange?: () => void; onReady: (h: CmmnDiagramHandle) => void; height?: number }) {
  const { t } = useTranslation();
  const host = useRef<HTMLDivElement>(null);
  const modeler = useRef<CmmnModelerApi | null>(null);
  const changeRef = useRef(onChange);
  changeRef.current = onChange;
  const parsed = useMemo(() => parseCmmn(xml), [xml]);
  const [selected, setSelected] = useState<CmmnNode | undefined>();
  const [outline, setOutline] = useState<CmmnNode[]>([]);
  const [name, setName] = useState('');
  const [linkTo, setLinkTo] = useState('');
  const [failed, setFailed] = useState<Error | null>(null);

  useEffect(() => {
    if (!host.current) return undefined;
    let api: CmmnModelerApi;
    try {
      api = createCmmnModeler(host.current, parsed.model, {
        takenIds: fileIds(parsed),
        onChange: () => {
          setOutline(api.exportModel().nodes);
          const s = api.selected();
          setSelected(s);
          setName(s?.name ?? '');
          changeRef.current?.();
        },
        onSelect: (n) => {
          setSelected(n);
          setName(n?.name ?? '');
          setLinkTo('');
        },
      });
    } catch (e) {
      setFailed(e as Error);
      return undefined;
    }
    modeler.current = api;
    setOutline(api.exportModel().nodes);
    // each getXml starts again from the original text, so saving twice gives the same file
    onReady({ getXml: () => serializeCmmn(parseCmmn(xml), api.exportModel()) });
    return () => {
      modeler.current = null;
      api.destroy();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [parsed]);

  const m = modeler.current;
  const kindLabel = (k: string) => t(`modeller.cmmnKind.${k}`);
  const targets = selected && m ? m.linkTargets(selected.id) : [];
  const canCriterion = (k: CriterionKind) => !!selected && canHostCriterion(selected.kind, k);
  const commitName = () => {
    if (selected && m && name !== selected.name) m.rename(selected.id, name);
  };
  const depthOf = (n: CmmnNode) => {
    let d = 0;
    for (let p = n.parent; p && d < 50; p = outline.find((o) => o.id === p)?.parent) d++;
    return d;
  };

  if (failed) return <p className="field-error" role="alert">{t('modeller.cmmnDiagramFailed', { message: failed.message })}</p>;

  return (
    <div className="cmmn-editor">
      {!parsed.model.hadLayout && <p className="hint">{t('modeller.cmmnLayoutGenerated')}</p>}
      {parsed.model.notShown.length > 0 && <p className="hint">{t('modeller.cmmnNotShown', { parts: parsed.model.notShown.join(', ') })}</p>}
      <div role="toolbar" aria-label={t('modeller.cmmnToolbar')} className="row wrap">
        {CREATABLE_KINDS.map((k) => (
          <button key={k} type="button" className="btn btn-sm" disabled={!m} onClick={() => m?.addPlanItem(k, kindLabel(k))}>
            {t('modeller.cmmnAdd', { kind: kindLabel(k) })}
          </button>
        ))}
        <button type="button" className="btn btn-sm" disabled={!canCriterion('entryCriterion')} onClick={() => m?.addCriterion('entryCriterion')}>
          {t('modeller.cmmnAddEntry')}
        </button>
        <button type="button" className="btn btn-sm" disabled={!canCriterion('exitCriterion')} onClick={() => m?.addCriterion('exitCriterion')}>
          {t('modeller.cmmnAddExit')}
        </button>
        <button type="button" className="btn btn-sm" disabled={!selected || selected.kind === 'casePlanModel'} onClick={() => m?.removeSelected()}>
          {t('modeller.cmmnDelete')}
        </button>
        <button type="button" className="btn btn-sm" disabled={!m} onClick={() => m?.undo()}>
          {t('modeller.cmmnUndo')}
        </button>
        <button type="button" className="btn btn-sm" disabled={!m} onClick={() => m?.redo()}>
          {t('modeller.cmmnRedo')}
        </button>
        <button type="button" className="btn btn-sm" disabled={!m} onClick={() => m?.fit()}>
          {t('modeller.cmmnFit')}
        </button>
      </div>
      <p className="hint small">{t('modeller.cmmnListenerNotOffered')}</p>
      <div className="grid-2">
        <div ref={host} className="cmmn-host" tabIndex={0} role="application" aria-label={t('modeller.cmmnCanvas')} style={{ minHeight: height, height }} />
        <div>
          <h3>{t('modeller.cmmnSelection')}</h3>
          {!selected && <p className="muted">{t('modeller.cmmnNoSelection')}</p>}
          {selected && (
            <>
              <p className="small">
                <span className="muted">{kindLabel(selected.kind)}</span> <code>{selected.id}</code>
              </p>
              <div className="field">
                <label htmlFor="cmmn-name">{t('modeller.cmmnName')}</label>
                <input
                  id="cmmn-name"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  onBlur={commitName}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') commitName();
                  }}
                />
              </div>
              {!isCriterion(selected.kind) && selected.kind !== 'casePlanModel' && (
                <div className="field">
                  <label htmlFor="cmmn-link">{t('modeller.cmmnLinkTo')}</label>
                  <div className="row">
                    <select id="cmmn-link" value={linkTo} onChange={(e) => setLinkTo(e.target.value)} disabled={!targets.length}>
                      <option value="">{targets.length ? t('modeller.cmmnLinkPick') : t('modeller.cmmnLinkNone')}</option>
                      {targets.map((c) => (
                        <option key={c.id} value={c.id}>
                          {`${kindLabel(c.kind)}: ${outline.find((o) => o.id === c.host)?.name || c.host}${c.name ? ` (${c.name})` : ''}`}
                        </option>
                      ))}
                    </select>
                    <button
                      type="button"
                      className="btn btn-sm"
                      disabled={!linkTo}
                      onClick={() => {
                        if (m?.link(selected.id, linkTo)) setLinkTo('');
                      }}
                    >
                      {t('modeller.cmmnLink')}
                    </button>
                  </div>
                  <p className="hint small">{t('modeller.cmmnLinkHint')}</p>
                </div>
              )}
            </>
          )}
          <h3>{t('modeller.cmmnOutline')}</h3>
          <ul className="plan-tree">
            {outline
              .filter((n) => !isCriterion(n.kind))
              .map((n) => (
                <li key={n.id} style={{ paddingInlineStart: `${depthOf(n)}em` }}>
                  <button type="button" className="link" aria-pressed={selected?.id === n.id} onClick={() => m?.select(n.id)}>
                    <span className="muted small">{kindLabel(n.kind)}</span> {n.name || n.id}
                  </button>
                  {ENGINE_REJECTED_KINDS.includes(n.kind as (typeof ENGINE_REJECTED_KINDS)[number]) && <span className="badge badge-warn">{t('modeller.cmmnListenerRejected')}</span>}
                </li>
              ))}
          </ul>
        </div>
      </div>
    </div>
  );
}
