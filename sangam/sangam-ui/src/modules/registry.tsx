// SPDX-License-Identifier: Apache-2.0
/**
 * sangam-registry screens (contract /registry/**): typed objects and types, number series (gap-free numbering),
 * registers (books, entries, corrections, hash verification, certified extracts), instruments (licences / permits /
 * certificates: issue, renew, deemed renewal, suspend, revoke, transfer, endorse) with a public verification page,
 * parties with duplicate review (match candidates, merge / unmerge), assets (classes, meters, plans, work orders) and
 * custody (chain of custody, transfers, seals, disposal, gap check).
 */
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { http } from '../api/client';
import { useAction } from '../lib/hooks';
import { LanguageSwitch } from '../app/Layout';
import { JsonView, Notice, ProblemAlert, TextField } from '../components/ui';
import { ModulePage, c, f, type ScreenDef } from './engine';

const objects: ScreenDef = {
  id: 'objects',
  title: 'mod.registry.objects',
  description: 'mod.registry.objectsDesc',
  list: '/registry/objects',
  filters: [f.text('typeKey'), f.text('state'), f.text('number'), f.text('holderPartyId'), f.text('subjectRef')],
  columns: [c('number'), c('typeKey'), c('kind'), c('state'), c('holderPartyId'), c('updatedAt')],
  rowKey: ['id', 'objectId', 'number'],
  detail: '/registry/objects/{id}',
  sections: [
    { id: 'transitions', label: 'mod.registry.availableTransitions', path: '/registry/objects/{id}/transitions' },
    { id: 'versions', label: 'mod.common.history', path: '/registry/objects/{id}/versions' },
    { id: 'audit', label: 'mod.common.verifyAudit', path: '/registry/objects/{id}/audit/verify' },
  ],
  create: { id: 'create', label: 'act.create', path: '/registry/objects', fields: [f.text('typeKey', undefined, true), f.text('holderPartyId'), f.text('subjectRef'), f.text('parentId'), f.json('attributes')] },
  actions: [
    { id: 'transition', label: 'act.transition', path: '/registry/objects/{id}/transitions/{name}', fields: [f.text('name', 'fld.transition', true), f.json('attributes')], reason: 'body' },
    { id: 'update', label: 'act.update', method: 'PATCH', path: '/registry/objects/{id}', fields: [f.json('attributes', undefined, true)] },
  ],
};

const types: ScreenDef = {
  id: 'types',
  title: 'mod.registry.types',
  description: 'mod.registry.typesDesc',
  list: '/registry/types',
  unpaged: true,
  filters: [f.select('kind', ['OBJECT', 'INSTRUMENT', 'REGISTER', 'PARTY', 'ASSET', 'CUSTODY'])],
  columns: [c('typeKey'), c('kind'), c('version'), c('state')],
  rowKey: ['typeKey', 'key'],
  detail: '/registry/types/{typeKey}',
  create: { id: 'publish', label: 'act.publishType', path: '/registry/types', fields: [f.json('definition', 'fld.definition', true)], noJson: true, hint: 'mod.registry.publishTypeHint' },
  actions: [{ id: 'retire', label: 'act.retire', path: '/registry/types/{typeKey}/versions/{version}/retire', reason: 'body', danger: true }],
};

const numbering: ScreenDef = {
  id: 'numbering',
  title: 'mod.registry.numbering',
  description: 'mod.registry.numberingDesc',
  list: '/registry/number-series',
  unpaged: true,
  columns: [c('seriesKey'), c('pattern'), c('resetPolicy'), c('scope'), c('state')],
  rowKey: ['seriesKey', 'key'],
  detail: '/registry/number-series/{seriesKey}',
  sections: [
    { id: 'gaps', label: 'mod.registry.gaps', path: '/registry/number-series/{seriesKey}/gaps' },
    { id: 'allocations', label: 'mod.registry.allocations', path: '/registry/number-series/{seriesKey}/allocations' },
  ],
  toolbar: [{ id: 'define', label: 'act.defineSeries', method: 'PUT', path: '/registry/number-series/{seriesKey}', fields: [f.text('seriesKey', undefined, true), f.text('pattern', undefined, true), f.select('resetPolicy', ['NEVER', 'YEARLY', 'FISCAL_YEAR', 'MONTHLY']), f.text('scope')], hint: 'mod.registry.patternHint' }],
  actions: [
    { id: 'allocate', label: 'act.allocate', path: '/registry/number-series/{seriesKey}/allocate', fields: [f.text('scope'), f.text('period'), f.text('reference')], showResult: true },
    { id: 'void', label: 'act.voidNumber', path: '/registry/number-series/{seriesKey}/void', fields: [f.text('number', undefined, true)], reason: 'body', danger: true },
    { id: 'used', label: 'act.markUsed', path: '/registry/number-series/{seriesKey}/mark-used', fields: [f.text('number', undefined, true), f.text('reference')] },
    { id: 'close', label: 'act.closePeriod', path: '/registry/number-series/{seriesKey}/close-period', fields: [f.text('scope'), f.text('period', undefined, true)], reason: 'body' },
  ],
};

const registers: ScreenDef = {
  id: 'registers',
  title: 'mod.registry.registers',
  description: 'mod.registry.registersDesc',
  list: '/registry/registers',
  unpaged: true,
  columns: [c('registerKey'), c('name'), c('scope'), c('state')],
  rowKey: ['registerKey', 'key'],
  detail: '/registry/registers/{registerKey}',
  sections: [{ id: 'books', label: 'mod.registry.books', path: '/registry/registers/{registerKey}/books' }],
  toolbar: [{ id: 'define', label: 'act.defineRegister', method: 'PUT', path: '/registry/registers/{registerKey}', fields: [f.text('registerKey', undefined, true), f.text('name', undefined, true), f.json('columns'), f.text('scope')] }],
  actions: [{ id: 'append', label: 'act.appendEntry', path: '/registry/registers/{registerKey}/entries', fields: [f.text('scope'), f.text('refType'), f.text('refId'), f.json('values', undefined, true)] }],
};

const books: ScreenDef = {
  id: 'books',
  title: 'mod.registry.bookEntries',
  description: 'mod.registry.booksDesc',
  list: '/registry/registers/{registerKey}/books',
  filters: [f.text('registerKey', undefined, true), f.text('scope')],
  requireFilters: ['registerKey'],
  columns: [c('bookId'), c('scope'), c('period'), c('state'), c('entries')],
  rowKey: ['bookId', 'id'],
  detail: '/registry/registers/books/{bookId}',
  sections: [
    { id: 'entries', label: 'mod.registry.entries', path: '/registry/registers/books/{bookId}/entries' },
    { id: 'verify', label: 'mod.common.verifyAudit', path: '/registry/registers/books/{bookId}/verify' },
  ],
  actions: [
    { id: 'correct', label: 'act.correctEntry', path: '/registry/registers/books/{bookId}/entries/{entryNo}/corrections', fields: [f.num('entryNo', undefined, true), f.json('values', undefined, true)], reason: 'body' },
    { id: 'extract', label: 'act.certifiedExtract', path: '/registry/registers/books/{bookId}/extracts', fields: [f.num('fromEntry'), f.num('toEntry'), f.text('purpose')], showResult: true },
    { id: 'close', label: 'act.closeBook', path: '/registry/registers/books/{bookId}/close', reason: 'body', danger: true },
  ],
};

const instrumentId = [f.text('id', 'fld.instrumentId', true)];
const instruments: ScreenDef = {
  id: 'instruments',
  title: 'mod.registry.instruments',
  description: 'mod.registry.instrumentsDesc',
  list: '/registry/instruments/{id}/endorsements',
  unpaged: true,
  filters: instrumentId,
  requireFilters: ['id'],
  emptyHint: 'mod.registry.instrumentLookup',
  columns: [c('endorsementNo'), c('kind'), c('text'), c('at')],
  rowKey: ['endorsementNo', 'id'],
  toolbar: [
    { id: 'issue', label: 'act.issue', path: '/registry/instruments', fields: [f.text('typeKey', undefined, true), f.text('holderPartyId', undefined, true), f.text('subjectRef'), f.date('validFrom'), f.date('validTo'), f.json('attributes')], showResult: true },
    { id: 'documents', label: 'act.documents', method: 'POST', path: '/registry/instruments/{id}/documents/regenerate', fields: [f.text('purpose', undefined, false, { inQuery: true })], showResult: true },
    { id: 'renew', label: 'act.renew', path: '/registry/instruments/{id}/renew', fields: [f.date('validTo', undefined, true)], showResult: true },
    { id: 'deemed', label: 'act.deemedRenewal', path: '/registry/instruments/{id}/deemed-renewal', reason: 'body', showResult: true },
    { id: 'suspend', label: 'act.suspend', path: '/registry/instruments/{id}/suspend', reason: 'body', danger: true },
    { id: 'reinstate', label: 'act.reinstate', path: '/registry/instruments/{id}/reinstate', reason: 'body' },
    { id: 'revoke', label: 'act.revoke', path: '/registry/instruments/{id}/revoke', reason: 'body', danger: true },
    { id: 'surrender', label: 'act.surrender', path: '/registry/instruments/{id}/surrender', reason: 'body' },
    { id: 'amend', label: 'act.amend', path: '/registry/instruments/{id}/amend', fields: [f.json('attributes', undefined, true)], reason: 'body' },
    { id: 'transfer', label: 'act.transferHolder', path: '/registry/instruments/{id}/transfer-holder', fields: [f.text('newHolderPartyId', undefined, true)], reason: 'body' },
    { id: 'duplicate', label: 'act.duplicate', path: '/registry/instruments/{id}/duplicate', reason: 'body', showResult: true },
    { id: 'endorse', label: 'act.endorse', path: '/registry/instruments/{id}/endorsements', fields: [f.text('kind'), f.area('text', undefined, true)] },
  ],
};

const parties: ScreenDef = {
  id: 'parties',
  title: 'mod.registry.parties',
  description: 'mod.registry.partiesDesc',
  list: '/registry/parties',
  filters: [f.select('kind', ['PERSON', 'ORGANISATION', 'HOUSEHOLD']), f.text('name'), f.text('idType'), f.text('idValue'), f.text('status')],
  columns: [c('displayName'), c('kind'), c('status'), c('primaryIdentifier'), c('updatedAt')],
  rowKey: ['id', 'partyId'],
  detail: '/registry/parties/{id}',
  sections: [
    { id: 'identifiers', label: 'mod.registry.identifiers', path: '/registry/parties/{id}/identifiers' },
    { id: 'addresses', label: 'mod.registry.addresses', path: '/registry/parties/{id}/addresses' },
    { id: 'relationships', label: 'mod.registry.relationships', path: '/registry/parties/{id}/relationships' },
    { id: 'household', label: 'mod.registry.household', path: '/registry/parties/{id}/household' },
    { id: 'merges', label: 'mod.registry.merges', path: '/registry/parties/{id}/merges' },
    { id: 'audit', label: 'mod.common.verifyAudit', path: '/registry/parties/{id}/audit/verify' },
  ],
  create: {
    id: 'create',
    label: 'act.create',
    path: '/registry/parties',
    fields: [f.select('kind', ['PERSON', 'ORGANISATION', 'HOUSEHOLD'], undefined, true), f.text('displayName', undefined, true), f.text('idType'), f.text('idValue'), f.json('attributes')],
    hint: 'mod.registry.matchOnCreate',
  },
  actions: [
    { id: 'update', label: 'act.update', method: 'PATCH', path: '/registry/parties/{id}', fields: [f.text('displayName'), f.json('attributes')] },
    { id: 'identifier', label: 'act.addIdentifier', path: '/registry/parties/{id}/identifiers', fields: [f.text('idType', undefined, true), f.text('idValue', undefined, true, { sensitive: true })] },
    { id: 'address', label: 'act.addAddress', path: '/registry/parties/{id}/addresses', fields: [f.text('kind'), f.area('line', 'fld.address', true), f.text('lgdCode'), f.text('pincode')] },
    { id: 'relationship', label: 'act.addRelationship', path: '/registry/parties/{id}/relationships', fields: [f.text('type', 'fld.relationshipType', true), f.text('toPartyId', undefined, true)] },
    { id: 'rematch', label: 'act.rematch', path: '/registry/parties/{id}/match', showResult: true },
    { id: 'snapshot', label: 'act.snapshot', path: '/registry/parties/{id}/snapshots', fields: [f.text('reference', undefined, true)], showResult: true },
    { id: 'deactivate', label: 'act.deactivate', path: '/registry/parties/{id}/deactivate', reason: 'body', danger: true },
    { id: 'reactivate', label: 'act.reactivate', path: '/registry/parties/{id}/reactivate', reason: 'body' },
  ],
};

const duplicates: ScreenDef = {
  id: 'duplicates',
  title: 'mod.registry.duplicates',
  description: 'mod.registry.duplicatesDesc',
  list: '/registry/parties/candidates',
  filters: [f.select('status', ['OPEN', 'CONFIRMED', 'REJECTED']), f.text('partyId')],
  columns: [c('candidateId'), c('partyId'), c('matchedPartyId'), c('score'), c('status'), c('reasons')],
  rowKey: ['candidateId', 'id'],
  actions: [
    { id: 'confirm', label: 'act.confirmDuplicate', path: '/registry/parties/candidates/{candidateId}/confirm', reason: 'body', visible: (r) => r.status !== 'CONFIRMED' },
    { id: 'reject', label: 'act.notDuplicate', path: '/registry/parties/candidates/{candidateId}/reject', reason: 'body', visible: (r) => r.status !== 'REJECTED' },
  ],
  toolbar: [
    { id: 'match', label: 'act.matchRecord', path: '/registry/parties/match', fields: [f.json('record', 'fld.record', true)], noJson: true, showResult: true },
    { id: 'merge', label: 'act.merge', path: '/registry/parties/merge', fields: [f.text('survivorId', undefined, true), f.text('mergedId', undefined, true)], reason: 'body', danger: true, hint: 'mod.registry.mergeHint' },
    { id: 'unmerge', label: 'act.unmerge', path: '/registry/parties/merges/{mergeEventId}/unmerge', fields: [f.text('mergeEventId', undefined, true)], reason: 'body' },
  ],
};

const assets: ScreenDef = {
  id: 'assets',
  title: 'mod.registry.assets',
  description: 'mod.registry.assetsDesc',
  list: '/registry/assets',
  filters: [f.text('classKey'), f.text('status'), f.text('locationRef'), f.text('custodianRef'), f.text('parentAssetId')],
  columns: [c('tag'), c('classKey'), c('name'), c('status'), c('locationRef'), c('custodianRef')],
  rowKey: ['id', 'assetId', 'tag'],
  detail: '/registry/assets/{id}',
  sections: [
    { id: 'movements', label: 'mod.registry.movements', path: '/registry/assets/{id}/movements' },
    { id: 'children', label: 'mod.registry.children', path: '/registry/assets/{id}/children' },
    { id: 'meters', label: 'mod.registry.meters', path: '/registry/assets/{id}/meters' },
    { id: 'plans', label: 'mod.registry.plans', path: '/registry/assets/{id}/plans' },
    { id: 'depreciation', label: 'mod.registry.depreciation', path: '/registry/assets/{id}/depreciation' },
    { id: 'bookValue', label: 'mod.registry.bookValue', path: '/registry/assets/{id}/book-value' },
    { id: 'reliability', label: 'mod.registry.reliability', path: '/registry/assets/{id}/reliability' },
  ],
  create: { id: 'register', label: 'act.register', path: '/registry/assets', fields: [f.text('classKey', undefined, true), f.text('tag'), f.text('name', undefined, true), f.text('parentAssetId'), f.text('locationRef'), f.text('custodianRef'), f.money('cost'), f.date('acquiredOn'), f.json('attributes')] },
  actions: [
    { id: 'move', label: 'act.move', path: '/registry/assets/{id}/move', fields: [f.text('toLocationRef'), f.text('toCustodianRef')], reason: 'body' },
    { id: 'reparent', label: 'act.reparent', path: '/registry/assets/{id}/reparent', fields: [f.text('parentAssetId', undefined, true)], reason: 'body' },
    { id: 'meter', label: 'act.addMeter', path: '/registry/assets/{id}/meters', fields: [f.text('meterType', undefined, true), f.text('unit')] },
    { id: 'reading', label: 'act.recordReading', path: '/registry/assets/{id}/meters/{meterType}/readings', fields: [f.text('meterType', undefined, true), f.money('value', 'fld.reading', true), f.dt('readAt')] },
    { id: 'plan', label: 'act.addPlan', path: '/registry/assets/{id}/plans', fields: [f.text('workType', undefined, true), f.text('interval'), f.json('condition')] },
    { id: 'dispose', label: 'act.dispose', path: '/registry/assets/{id}/dispose', fields: [f.text('method'), f.money('proceeds')], reason: 'body', danger: true },
  ],
};

const workOrders: ScreenDef = {
  id: 'workOrders',
  title: 'mod.registry.workOrders',
  list: '/registry/assets/work-orders',
  filters: [f.text('assetId'), f.text('state'), f.text('workType')],
  columns: [c('woId'), c('assetId'), c('workType'), c('state'), c('dueAt')],
  rowKey: ['woId', 'id'],
  detail: '/registry/assets/work-orders/{woId}',
  create: { id: 'create', label: 'act.create', path: '/registry/assets/work-orders', fields: [f.text('assetId', undefined, true), f.text('workType', undefined, true), f.dt('dueAt'), f.area('description')] },
  toolbar: [{ id: 'generate', label: 'act.generateDue', path: '/registry/assets/work-orders/generate-due', showResult: true, noJson: true }],
  actions: [{ id: 'step', label: 'act.step', path: '/registry/assets/work-orders/{woId}/{step}', fields: [f.select('step', ['assign', 'start', 'hold', 'complete', 'verify', 'close', 'cancel'], 'fld.step', true)], reason: 'body' }],
};

const custody: ScreenDef = {
  id: 'custody',
  title: 'mod.registry.custody',
  description: 'mod.registry.custodyDesc',
  list: '/registry/custody/items',
  filters: [f.text('typeKey'), f.text('custodian'), f.text('caseRef'), f.text('label'), f.text('state')],
  columns: [c('label'), c('typeKey'), c('custodian'), c('state'), c('caseRef'), c('sealed')],
  rowKey: ['id', 'itemId', 'label'],
  detail: '/registry/custody/items/{id}',
  sections: [
    { id: 'events', label: 'mod.registry.custodyEvents', path: '/registry/custody/items/{id}/events' },
    { id: 'transfers', label: 'mod.registry.transfers', path: '/registry/custody/items/{id}/transfers' },
    { id: 'disposal', label: 'mod.registry.disposalOrders', path: '/registry/custody/items/{id}/disposal-orders' },
    { id: 'verify', label: 'mod.common.verifyAudit', path: '/registry/custody/items/{id}/verify' },
  ],
  create: { id: 'register', label: 'act.register', path: '/registry/custody/items', fields: [f.text('typeKey', undefined, true), f.text('label'), f.text('caseRef'), f.text('custodian', undefined, true), f.area('description')] },
  toolbar: [{ id: 'gap', label: 'act.gapCheck', method: 'GET', path: '/registry/custody/gap-check', fields: [f.num('inTransitHours'), f.num('staleDays')], showResult: true, noJson: true }],
  actions: [
    { id: 'transfer', label: 'act.initiateTransfer', path: '/registry/custody/items/{id}/transfers', fields: [f.text('toCustodian', undefined, true), f.text('purpose')], reason: 'body' },
    { id: 'handover', label: 'act.handover', path: '/registry/custody/items/{id}/handover', fields: [f.text('toCustodian', undefined, true)], reason: 'body' },
    { id: 'seal', label: 'act.seal', path: '/registry/custody/items/{id}/seal', fields: [f.text('sealNo', undefined, true)] },
    { id: 'breakSeal', label: 'act.breakSeal', path: '/registry/custody/items/{id}/break-seal', fields: [f.text('sealNo')], reason: 'body', danger: true },
    { id: 'inspect', label: 'act.inspect', path: '/registry/custody/items/{id}/inspect', fields: [f.area('findings')] },
    { id: 'location', label: 'act.setLocation', path: '/registry/custody/items/{id}/location', fields: [f.text('location', undefined, true)] },
    { id: 'container', label: 'act.putInto', path: '/registry/custody/items/{id}/container', fields: [f.text('containerId', undefined, true)] },
    { id: 'takeOut', label: 'act.takeOut', path: '/registry/custody/items/{id}/take-out', reason: 'body' },
    { id: 'dispose', label: 'act.orderDisposal', path: '/registry/custody/items/{id}/disposal-orders', fields: [f.text('method', undefined, true), f.text('orderRef', undefined, true)], reason: 'body', danger: true },
  ],
};

const pendingTransfers: ScreenDef = {
  id: 'pending',
  title: 'mod.registry.pendingTransfers',
  list: '/registry/custody/transfers/pending',
  filters: [f.text('toCustodian')],
  columns: [c('transferId'), c('itemId'), c('fromCustodian'), c('toCustodian'), c('initiatedAt')],
  rowKey: ['transferId', 'id'],
  actions: [
    { id: 'accept', label: 'act.accept', path: '/registry/custody/transfers/{transferId}/accept', fields: [f.area('condition', 'fld.conditionOnReceipt')] },
    { id: 'reject', label: 'act.reject', path: '/registry/custody/transfers/{transferId}/reject', reason: 'body', danger: true },
    { id: 'cancel', label: 'act.cancel', path: '/registry/custody/transfers/{transferId}/cancel', reason: 'body' },
  ],
};

export function RegistryPage() {
  return (
    <ModulePage
      title="mod.registry.title"
      description="mod.registry.desc"
      screens={[objects, types, numbering, registers, books, instruments, parties, duplicates, assets, workOrders, custody, pendingTransfers]}
    />
  );
}

/**
 * Public verification of an instrument or a register extract (no sign-in; the server rate-limits). Reachable at
 * /verify (and /verify/:tenant?code=...) so a QR code on a certificate can open it directly.
 */
export function PublicVerify() {
  const { t } = useTranslation();
  const params = useParams();
  const search = new URLSearchParams(window.location.search);
  const [tenant, setTenant] = useState(params.tenant ?? search.get('tenant') ?? '');
  const [code, setCode] = useState(search.get('code') ?? '');
  const [kind, setKind] = useState<'instrument' | 'extract'>(search.get('kind') === 'extract' ? 'extract' : 'instrument');
  const [result, setResult] = useState<Record<string, unknown>>();
  const act = useAction();
  const verify = () =>
    void act
      .run(() =>
        http.get<Record<string, unknown>>(
          kind === 'instrument' ? `/registry/instruments/public/${encodeURIComponent(tenant)}/verify` : `/registry/registers/public/${encodeURIComponent(tenant)}/extracts/verify`,
          { code },
        ),
      )
      .then((r) => r && setResult(r));
  const valid = result && (result.valid === true || result.status === 'VALID' || result.state === 'ACTIVE');
  return (
    <main id="main" className="signin" tabIndex={-1}>
      <div className="signin-card">
        <LanguageSwitch />
        <h1 id="page-title" tabIndex={-1}>
          {t('mod.verify.title')}
        </h1>
        <p>{t('mod.verify.intro')}</p>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            verify();
          }}
        >
          <fieldset className="field">
            <legend>{t('mod.verify.what')}</legend>
            <label className="choice">
              <input type="radio" name="vk" checked={kind === 'instrument'} onChange={() => setKind('instrument')} /> {t('mod.verify.instrument')}
            </label>
            <label className="choice">
              <input type="radio" name="vk" checked={kind === 'extract'} onChange={() => setKind('extract')} /> {t('mod.verify.extract')}
            </label>
          </fieldset>
          <TextField label={t('mod.verify.issuer')} value={tenant} onChange={setTenant} required />
          <TextField label={t('mod.verify.code')} value={code} onChange={setCode} required autoComplete="off" />
          <button type="submit" className="primary" disabled={!tenant || !code || act.busy}>
            {t('mod.verify.verify')}
          </button>
        </form>
        <ProblemAlert error={act.error} />
        {result && (
          <>
            <Notice tone={valid ? 'ok' : 'bad'}>{valid ? t('mod.verify.valid') : t('mod.verify.notValid')}</Notice>
            <JsonView value={result} />
          </>
        )}
        <p className="muted small">{t('mod.verify.privacy')}</p>
      </div>
    </main>
  );
}
