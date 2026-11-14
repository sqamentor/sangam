"""Targeted edits for the approve-401 / connector-contract work. Each edit must match exactly once."""
import sys, os
ROOT = sys.argv[1]


def edit(rel, old, new):
    p = os.path.join(ROOT, rel)
    s = open(p, encoding='utf-8', newline='').read()
    # files may mix line endings: use the form of the old text that is present
    crlf = s.count(old) == 0 and s.count(old.replace('\n', '\r\n')) > 0
    o, n = (old.replace('\n', '\r\n'), new.replace('\n', '\r\n')) if crlf else (old, new)
    if s.count(o) == 0 and (new in s or new.replace('\n', '\r\n') in s):
        print('already', rel)
        return
    assert s.count(o) == 1, (rel, s.count(o), old[:80])
    open(p, 'w', encoding='utf-8', newline='').write(s.replace(o, n))
    print('ok', rel)


edit('sangam-packs/src/main/java/org/sangam/packs/core/Vars.java',
'''        for (String f : names) {
            m.put(f, canonical(s.getVariable(f)));
        }
        return m;''',
'''        for (String f : names) {
            // "variable>parameter": the process variable is sent under the connector's contract name (the same
            // source>target form as call-activity mappings), so a pack's vocabulary never has to match a connector's
            int gt = f.indexOf('>');
            String var = gt < 0 ? f : f.substring(0, gt).trim();
            String param = gt < 0 ? f : f.substring(gt + 1).trim();
            if (var.isEmpty() || param.isEmpty()) {
                throw new IllegalArgumentException("connector input '" + f + "' must be 'variable' or 'variable>parameter'");
            }
            m.put(param, canonical(s.getVariable(var)));
        }
        return m;''')

edit('sangam-packs/src/main/java/org/sangam/packs/core/ConnectorCallDelegate.java',
''' *   <li>{@code inputs}: comma list of variables sent as the payload (default: the material fields);</li>''',
''' *   <li>{@code inputs}: comma list of variables sent as the payload (default: the material fields); an entry
 *       {@code variable>parameter} sends the variable under the connector's contract name;</li>''')

edit('sangam-packs/src/main/java/org/sangam/packs/sim/SimulatorConnectorGateway.java',
'''            case "court.limbs" -> d.put("nextHearing", java.time.LocalDate.now().plusDays(30).toString());
            default -> d.put("ok", true);''',
'''            case "court.limbs" -> d.put("nextHearing", java.time.LocalDate.now().plusDays(30).toString());
            // sangam-config's contract (ConfigConnector), simulated: a result only from the contract's inputs, so a
            // process that does not send them is not validated by the simulator either (ADR-D-26)
            case "config.documentType" -> {
                switch (String.valueOf(r.operation())) {
                    case "documentType.get" -> {
                        Object form = p.get("typeId") != null ? p.get("typeId") : p.get("formNumber");
                        if (form != null) {
                            d.put("id", "SIM_" + form);
                            d.put("formNumber", form);
                            d.put("approvalChain", "SIM_CHAIN");
                        }
                    }
                    case "documentType.check" -> d.put("valid", p.get("typeId") != null);
                    case "chain.signatures" -> {
                        if (p.get("chainId") != null && p.get("amount") != null) {
                            d.put("signatures", 1);
                        }
                    }
                    default -> d.put("ok", true);
                }
            }
            case "config.classification" -> d.put("valid", p.get("code") != null);
            default -> d.put("ok", true);''')

G = 'sangam-packs/tools/packgen/gov_ifms.py'
edit(G, "defaults='chainId=PAO_3;tokenless=false;", "defaults='tokenSeries=TOKEN;tokenless=false;")
edit(G, "secondSignatorySeat=SS-1;chainSignatures=1;amount=0;'", "secondSignatorySeat=SS-1;amount=0;'")
edit(G, "'billType,ddoCode', 'token', step='token')", "'tokenSeries>series,ddoCode>unit', 'token', step='token')")
edit(G, """    sig = s.conn('pc_sigs', 'Signatures required (chain template)', 'config.documentType', 'chain.signatures', 'query',
                 'chainId,amount', 'chain')""",
"""    # the chain is the bill type's approval chain (documentType.get -> docApprovalChain, passed in by the bill);
    # no default chain: an unknown chain fails the lookup instead of counting one signature
    sig = s.conn('pc_sigs', 'Signatures required (chain template)', 'config.documentType', 'chain.signatures', 'query',
                 'docApprovalChain>chainId,amount', 'chain')""")
edit(G, "'docValid=true;clsValid=true;dupDuplicate=false;", "'dupDuplicate=false;")
edit(G, "docPaymentRail=DEFAULT;docApprovalChain=PAO_3;'", "docPaymentRail=DEFAULT;'")
edit(G, """    typ = s.conn('bl_type', 'Bill type from the catalogue', 'config.documentType', 'documentType.get', 'query', 'billType', 'doc')
    cls = s.conn('bl_cls', 'Validate head of account', 'config.classification', 'classification.validate', 'query',
                 'headOfAccount', 'cls')
    chk = s.conn('bl_check', 'Check bill against its type', 'config.documentType', 'documentType.check', 'query',
                 'billType,headOfAccount,amount,sanctionRef,claimPeriod', 'doc')""",
"""    # inputs use the connector contract names ('variable>parameter' renames): the DDO's bill type is the form number
    # of the catalogue (FVC, GA-55 ...); documentType.get returns the type row (docId = catalogue id) and the check
    # runs against that id; the head of account is the classification code (scheme: the profile's defaultScheme).
    # No validation result has a passing default (ADR-D-26): a check that could not run stops the case (incident).
    typ = s.conn('bl_type', 'Bill type from the catalogue', 'config.documentType', 'documentType.get', 'query',
                 'billType>formNumber', 'doc')
    cls = s.conn('bl_cls', 'Validate head of account', 'config.classification', 'classification.validate', 'query',
                 'headOfAccount>code', 'cls')
    chk = s.conn('bl_check', 'Check bill against its type', 'config.documentType', 'documentType.check', 'query',
                 'docId>typeId,headOfAccount,amount,sanctionRef,claimPeriod', 'doc')""")
edit(G, "defaults='amount=0;receiptDeskSeat=AAO-1;scrollMatch=MATCHED;scrollAmount=0')",
        "defaults='challanSeries=CHALLAN;amount=0;receiptDeskSeat=AAO-1;scrollMatch=MATCHED;scrollAmount=0')")
edit(G, "'series.next', 'submit', 'receiptHead', 'chl', step='number')",
        "'series.next', 'submit',\n                 'challanSeries>series,receiptHead>unit', 'chl', step='number')")

C = 'sangam-config/src/main/java/org/sangam/config/api/ConfigConnector.java'
edit(C, ''' *   <li>{@code classification.validate}: {@code scheme}, {@code code} → {@code valid}, {@code findings},
 *       {@code attributes}, segment values;</li>''',
''' *   <li>{@code classification.validate}: {@code scheme} (default: profile {@code classification.defaultScheme}),
 *       {@code code} → {@code valid}, {@code findings}, {@code attributes}, segment values;</li>''')
edit(C, ''' *   <li>{@code documentType.check}: {@code typeId}, {@code document} → {@code valid}, {@code findings};</li>''',
''' *   <li>{@code documentType.check}: {@code typeId}, {@code document} (default: the call's own fields) →
 *       {@code valid}, {@code findings};</li>''')
edit(C, ''' *   <li>{@code chain.signatures}: {@code chainId}, {@code amount} → {@code signatures};</li>''',
''' *   <li>{@code chain.signatures}: {@code chainId}, {@code amount} → {@code signatures} (unknown chain: CHAIN_UNKNOWN);</li>''')
edit(C, '''                    String schemeId = r.str("scheme");
''', '''                    // a call naming only the code (a bill's head of account) uses the profile's classification.defaultScheme
                    String schemeId = r.str("scheme") != null ? r.str("scheme") : p.str("classification.defaultScheme", null);
                    if (schemeId == null) {
                        return ConnectorResult.BusinessFailure.of("SCHEME_UNKNOWN",
                                "no scheme given and no classification.defaultScheme in the profile");
                    }
                    if (r.str("code") == null) {
                        return ConnectorResult.BusinessFailure.of("CODE_MISSING", "no classification code to validate");
                    }
''')
edit(C, '''                    List<Finding> f = cat.check(r.str("typeId"), Tree.map(r.input().get("document")), asOf, null);''',
'''                    // the document is the 'document' map, else the call's own fields (a process sends its bill fields)
                    Map<String, Object> doc = r.input().get("document") instanceof Map<?, ?>
                            ? Tree.map(r.input().get("document")) : new LinkedHashMap<>(r.input());
                    List<Finding> f = cat.check(r.str("typeId"), doc, asOf, null);''')
edit(C, '''                    int n = cat.chain(r.str("chainId")).map(c -> c.signaturesFor(new BigDecimal(r.str("amount")))).orElse(1);
                    out.put("signatures", n);''',
'''                    // an unknown chain is a failure, never a default of one signature (ADR-D-26); the amount is the
                    // pack's number as given (its unit is the catalogue's band unit)
                    var chain = r.str("chainId") == null ? java.util.Optional.<DocumentTypeCatalogue.ChainTemplate>empty()
                            : cat.chain(r.str("chainId"));
                    if (chain.isEmpty()) {
                        return ConnectorResult.BusinessFailure.of("CHAIN_UNKNOWN",
                                "approval chain '" + r.str("chainId") + "' not in the catalogue");
                    }
                    if (r.str("amount") == null) {
                        return ConnectorResult.BusinessFailure.of("AMOUNT_MISSING", "no amount for the signature count");
                    }
                    out.put("signatures", chain.get().signaturesFor(new BigDecimal(r.str("amount"))));''')

# BL-1: the catalogue check (not a preset variable the check overwrites) reports the mixed-head bill invalid
edit(G, """              'GOI profile, bill type CONTINGENT', 'lines carry 2054 and 2052', 'BILL_INVALID with RPR SI 2.2(5)(g)',
              {'docValid': False})""",
"""              'GOI profile, bill type CONTINGENT', 'lines carry 2054 and 2052', 'BILL_INVALID with RPR SI 2.2(5)(g)',
              {'sim_config_documentType': 'valid=false'})""")
