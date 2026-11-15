# SPDX-License-Identifier: Apache-2.0
"""Cited regulatory tables: every row of a regulatory DMN states its citation and its evidence (decision 288).

A pack writes such a table with table(pk, ...) instead of pk.dmn(...): the evidence columns of decision 288
(evidenceStatus, permittedUse, evidenceNote, rounding.EVIDENCE_COLUMNS) and the citation column `source` are appended
to every row, so a case that ran keeps, in its decision history, which rule it applied and how far that rule was
verified. Generation stops on a row without a citation, on an evidence status or permitted use outside decision 288
(rounding.check_evidence: VERIFIED only when the primary text was read, PRODUCTION only with VERIFIED evidence), and on
a table with a row not for PRODUCTION that does not declare its placeholder: such a table is a pack placeholder
(Pack.placeholder), so readiness blocks go-live of every tenant that enables the pack until the tenant deploys a
confirmed override (GP-37). A source that was cited but not read says so (CITED_NOT_READ); a demonstration value names
no source (DEMO_SOURCE). Values go into FEEL string literals, so a text never contains a double quote.
"""
from lib import q
import rounding

VERIFIED, UNVERIFIED = rounding.VERIFIED, rounding.UNVERIFIED
PRODUCTION, DEMO_ONLY = rounding.PRODUCTION, rounding.DEMO_ONLY
SOURCE_COLUMN = ('source', 'Source (citation)', 'string')
COLUMNS = list(rounding.EVIDENCE_COLUMNS) + [SOURCE_COLUMN]
CITED_NOT_READ = 'cited, not read: '
DEMO_SOURCE = rounding.DEMO_SOURCE


def row(ins, outs, evidence, use, note, source, provision='', frm=rounding.NO_EFFECTIVE_DATE, annotation=''):
    """One table row: input tests, output entries (FEEL), its evidence, permitted use, the reason (note) and the
    citation (source; provision and the explicit effective date frm are required for a VERIFIED row)."""
    return dict(ins=list(ins), outs=list(outs), evidence=evidence, use=use, note=note, source=source,
                provision=provision, frm=frm, annotation=annotation)


def check_row(where, r):
    """Raises ValueError for a row that has no citation or whose evidence and use break decision 288."""
    source = (r['source'] or '').strip()
    if not source:
        raise ValueError(f'{where}: a regulatory row names its source (citation), or DEMO_SOURCE for a demonstration '
                         f'value')
    for name in ('note', 'source', 'provision'):
        if '"' in (r[name] or ''):
            raise ValueError(f'{where}: {name} contains a double quote (it is a FEEL string literal)')
    if source.startswith(CITED_NOT_READ) and r['evidence'] == VERIFIED:
        raise ValueError(f'{where}: a source that was not read is never VERIFIED (decision 288)')
    if source == DEMO_SOURCE and r['evidence'] == VERIFIED:
        raise ValueError(f'{where}: a demonstration value is never VERIFIED')
    rounding.check_evidence(where, r['evidence'], r['use'], source, r['provision'], r['frm'], r['note'])


def table(pk, did, name, hit, inputs, outputs, rows, doc, placeholder=None):
    """Writes the cited DMN `did` of pack `pk`. placeholder = (rule, reason) is required when a row is not for
    PRODUCTION and is then declared with pk.placeholder(did, rule, reason)."""
    if not rows:
        raise ValueError(f'{pk.id} {did}: a cited table has rows')
    names = {o[0] for o in outputs}
    clash = names & {c[0] for c in COLUMNS}
    if clash:
        raise ValueError(f'{pk.id} {did}: outputs {sorted(clash)} are the evidence and citation columns')
    dmn_rows = []
    for i, r in enumerate(rows):
        where = f'{pk.id} {did} row {i}'
        if len(r['ins']) != len(inputs) or len(r['outs']) != len(outputs):
            raise ValueError(f'{where}: {len(r["ins"])} tests / {len(r["outs"])} outputs for {len(inputs)} inputs / '
                             f'{len(outputs)} outputs')
        check_row(where, r)
        dmn_rows.append((r['ins'], r['outs'] + [q(r['evidence']), q(r['use']), q(r['note']), q(r['source'].strip())],
                         (r['annotation'] + ' ' if r['annotation'] else '') + f'{r["evidence"]} {r["use"]}'))
    demo = [i for i, r in enumerate(rows) if r['use'] != PRODUCTION]
    if demo and not placeholder:
        raise ValueError(f'{pk.id} {did}: rows {demo} are not for PRODUCTION, so the table declares its placeholder '
                         f'(readiness blocks go-live until a confirmed tenant override)')
    pk.dmn(did, name, hit, inputs, list(outputs) + COLUMNS, dmn_rows, doc=doc)
    if demo:
        pk.placeholder(did, placeholder[0], placeholder[1])
