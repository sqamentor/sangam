import sys


def edit(path, R):
    raw = open(path, 'rb').read().decode('utf-8')
    crlf = '\r\n' in raw
    t = raw.replace('\r\n', '\n')
    for old, new in R:
        n = t.count(old)
        if n != 1:
            raise SystemExit(f'{path}: marker found {n} times: {old[:80]!r}')
        t = t.replace(old, new)
    if crlf:
        t = t.replace('\n', '\r\n')
    open(path, 'wb').write(t.encode('utf-8'))
    print('ok', path, 'crlf' if crlf else 'lf')


gov3, gov1 = sys.argv[1], sys.argv[2]

edit(gov3, [
    ('''    s.seq(st, d, rv, w, t)
    g = s.xgw('rr_gw', 'Decision?')
    s.flow(t, g)
''', '''    s.seq(st, d, rv, w, t)
    # decision 299 (AU-03): RECORD_REVIEWED once per review decision, before the decision routes
    rd = s.svc('rr_reviewed', 'Record the review', C['CASE'], {'op': 'REVIEW'})
    g = s.xgw('rr_gw', 'Decision?')
    s.seq(t, rd, g)
'''),
    ('''    nai = s.conn('rr_nai', 'Transfer to National Archives (PDF/A)', 'archive.nai', 'transfer', 'submit',
                 'fileNo,subject,retentionCategory', 'nai', step='nai')
    s.flow(g, nai, "reviewDecision == 'transferNai'", 'transfer')
    s.flow(nai, s.end('rr_endTransferred', 'Transferred'))
''', '''    # decision 299: the transfer is the core execution block, so a transfer only queued in the outbox waits for its
    # settled outcome (result message, lookup or reconciliation) instead of ending the record as transferred; only a
    # transfer settled as succeeded writes RECORD_TRANSFERRED_NAI, any other outcome returns the record to the review
    nc = s.setv('rr_naiCfg', 'Transfer step', values='executionConnector=archive.nai;executionStep=nai;'
                'executionOperation=transfer;verifyApproval=false;reconcileRequired=false;'
                'executionPayloadFields=fileNo,subject,retentionCategory')
    nai = core(s, 'rr_nai', 'Transfer to National Archives (PDF/A) (core execution)', 'execution')
    s.flow(nc, nai)
    s.flow(g, nc, "reviewDecision == 'transferNai'", 'transfer')
    nd = s.svc('rr_naiDone', 'Record the transfer', C['CASE'], {'op': 'NAI_TRANSFERRED'})
    s.flow(nd, s.end('rr_endTransferred', 'Transferred'))
    s.flow(nai, outcome(s, 'rr_naiGw', 'executionOutcome', {'DONE': nd}, t, 'Transferred?'))
'''),
    ('''    s.flow(apg, nai, "appraisalDecision == 'transferNai'", 'archival value')
''', '''    s.flow(apg, nc, "appraisalDecision == 'transferNai'", 'archival value')
'''),
])

edit(gov1, [
    ('''    a = rt2.start('p2_rtiStart', 'RTI linked', msg='sangam.rtiLinked', interrupting=False)
    b = rt2.svc('p2_rtiHold', 'Legal hold while the RTI is open', C['CASE'], {'op': 'LEGAL_HOLD'})
    c = rt2.end('p2_rtiEnd', 'Hold set')
    rt2.seq(a, b, c)
''', '''    a = rt2.start('p2_rtiStart', 'RTI linked', msg='sangam.rtiLinked', interrupting=False)
    # decision 299 (J-P2-E10): RTI_REQUEST_LINKED once per linked request (rtiRequestRef of the message), then the hold
    link = rt2.svc('p2_rtiLink', 'Record the RTI link', C['CASE'], {'op': 'RTI_LINK'})
    b = rt2.svc('p2_rtiHold', 'Legal hold while the RTI is open', C['CASE'], {'op': 'LEGAL_HOLD'})
    c = rt2.end('p2_rtiEnd', 'Hold set')
    rt2.seq(a, link, b, c)
'''),
])
