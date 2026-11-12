// SPDX-License-Identifier: Apache-2.0
/**
 * M-068: the modeller's operation picker. It chooses an operation of an OpenAPI or AsyncAPI contract stored in the
 * package and gives the value of sangam:operationRef in the form OperationRef.java parses, contractId[@version]#operationId.
 * It reads GET /packages/{id}/contracts (listApiContracts: the latest version of each contract) and
 * GET /packages/{id}/contracts/{contractId}/operations?version (listApiContractOperations: the operations of exactly that
 * version), so a pinned reference names the version whose operations were shown. "Follow the latest version" writes
 * contractId#operationId instead, and the deployment pins the latest stored version (OperationRefPinning).
 *
 * The picker only fills a template field: the element-template panel shows it for every field bound to
 * sangam:operationRef (template sangam.connector.operation-ref, ConnectorTemplates.java), and the server's template
 * apply validates the value and writes it, so the picker never writes into the diagram itself.
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/endpoints';
import type { ApiContractSummary, ContractOperation } from '../api/types';
import { useAsync } from '../lib/hooks';
import { Checkbox, Loading, Notice, ProblemAlert, SelectField } from './ui';

/** The template binding the picker serves. */
export const OPERATION_REF_BINDING = 'sangam:operationRef';

/** contractId@version#operationId, or contractId#operationId when no version is pinned. */
export function operationRef(contractId: string, operationId: string, version?: number): string {
  return `${contractId}${version === undefined ? '' : `@${version}`}#${operationId}`;
}

/** What an operation does, as its contract states it: method and path (OpenAPI) or action and channel (AsyncAPI). */
export function operationLabel(o: ContractOperation): string {
  const what = (o.format === 'ASYNCAPI' ? [o.action, o.channel] : [o.method?.toUpperCase(), o.path]).filter(Boolean).join(' ');
  return [o.operationId, what && `(${what})`, o.summary && `· ${o.summary}`].filter(Boolean).join(' ');
}

const contractLabel = (c: ApiContractSummary) => `${c.contractId} v${c.version} · ${c.format}${c.specVersion ? ` ${c.specVersion}` : ''}`;

export function OperationRefPicker({ packageId, onPick }: { packageId: string; onPick: (ref: string) => void }) {
  const { t } = useTranslation();
  const contracts = useAsync(() => api.packages.contracts(packageId), [packageId]);
  const [contractId, setContractId] = useState('');
  const chosen = (contracts.data ?? []).find((c) => c.contractId === contractId);
  return (
    <fieldset className="stack">
      <legend>{t('opref.legend')}</legend>
      <ProblemAlert error={contracts.error} onRetry={contracts.reload} />
      {contracts.loading && !contracts.data ? (
        <Loading slow={contracts.slow} onRetry={contracts.reload} />
      ) : contracts.data && contracts.data.length === 0 ? (
        <Notice>{t('opref.none')}</Notice>
      ) : (
        contracts.data && (
          <>
            <SelectField
              label={t('opref.contract')}
              value={contractId}
              onChange={setContractId}
              placeholder={t('opref.choose')}
              options={contracts.data.map((c) => ({ value: c.contractId, label: contractLabel(c) }))}
            />
            {/* keyed by contract version: a new choice starts with its own operation list and nothing picked */}
            {chosen && <ContractOperations key={`${chosen.contractId}@${chosen.version}`} packageId={packageId} contract={chosen} onPick={onPick} />}
          </>
        )
      )}
    </fieldset>
  );
}

function ContractOperations({ packageId, contract, onPick }: { packageId: string; contract: ApiContractSummary; onPick: (ref: string) => void }) {
  const { t } = useTranslation();
  const ops = useAsync(() => api.packages.contractOperations(packageId, contract.contractId, contract.version), [packageId, contract.contractId, contract.version]);
  const [operationId, setOperationId] = useState('');
  const [follow, setFollow] = useState(false);
  const pick = (opId: string, followLatest: boolean) => {
    setOperationId(opId);
    setFollow(followLatest);
    if (opId) onPick(operationRef(contract.contractId, opId, followLatest ? undefined : contract.version));
  };
  return (
    <>
      <ProblemAlert error={ops.error} onRetry={ops.reload} />
      {ops.loading && !ops.data ? (
        <Loading slow={ops.slow} onRetry={ops.reload} />
      ) : ops.data && ops.data.length === 0 ? (
        <Notice>{t('opref.noOperations')}</Notice>
      ) : (
        ops.data && (
          <SelectField
            label={t('opref.operation')}
            value={operationId}
            onChange={(v) => pick(v, follow)}
            placeholder={t('opref.choose')}
            options={ops.data.map((o) => ({ value: o.operationId, label: operationLabel(o) }))}
          />
        )
      )}
      <Checkbox label={t('opref.followLatest')} hint={t('opref.followLatestHint', { version: contract.version })} checked={follow} onChange={(v) => pick(operationId, v)} />
    </>
  );
}
