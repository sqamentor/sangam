R = 'D:/GITHUB  Project/Custom WorkFlow/sangam/'
G = R + 'sangam-packs/tools/packgen/'


def sub(path, pairs):
    p = R + path if not path.startswith('D:') else path
    s = open(p, encoding='utf-8', newline='').read()
    for a, b in pairs:
        assert a in s, a
        s = s.replace(a, b)
    open(p, 'w', encoding='utf-8', newline='').write(s)


# ---- LedgerConnector
sub('sangam-ledger/src/main/java/org/sangam/ledger/LedgerConnector.java', [
    (""" *   <li>target for transfer: line {@code toHead}; for distribute""",
     """ *   <li>target for transfer: line {@code toHead} and / or holder {@code toHolder}; for distribute"""),
    ("""List.of("available", "allocate", "distribute", "withdraw", "commit", "encumber", "release", "consume", "transfer",
                        "adjust", "releaseHolds", "closePeriod")""",
     """List.of("available", "allocate", "distribute", "withdraw", "commit", "encumber", "release", "consume", "transfer",
                        "adjust", "reverse", "write_off", "releaseHolds", "closePeriod")"""),
    ("""            target = acct.withLine(first(r, List.of("toHead", "toLine"), "-"));""",
     """            String toLine = first(r, List.of("toHead", "toLine"), null);
            String toHolder = first(r, List.of("toHolder"), null);
            if (toLine == null && toHolder == null) {
                toLine = "-";
            }
            target = toLine == null ? acct : acct.withLine(toLine);
            if (toHolder != null) {
                target = target.atStage(target.stage(), toHolder);
            }"""),
])

# ---- X12Decoders
sub('sangam-connectors-health/src/main/java/org/sangam/connectors/health/x12/X12Decoders.java', [
    ("""        m.put("claims", claims);
""", """        for (Map<String, Object> c : claims) {
            // [U] claim balance CLP03 - sum(CAS) = CLP04: from knowledge of the 835 TR3, no public text could be read
            Map<String, String> totals = new LinkedHashMap<>();
            BigDecimal adjSum = BigDecimal.ZERO;
            if (c.get("adjustments") instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> a) {
                        BigDecimal v = dec(String.valueOf(a.get("amount")));
                        adjSum = adjSum.add(v);
                        totals.merge(String.valueOf(a.get("group")), v.toPlainString(),
                                (x, y) -> new BigDecimal(x).add(new BigDecimal(y)).toPlainString());
                    }
                }
            }
            c.put("adjustmentTotals", totals);
            c.put("claimBalanced", dec(String.valueOf(c.get("chargeAmount"))).subtract(adjSum)
                    .compareTo(dec(String.valueOf(c.get("paidAmount")))) == 0);
        }
        m.put("claims", claims);
"""),
    ("""            m.put("paidAmount", c0.get("paidAmount"));
""", """            m.put("paidAmount", c0.get("paidAmount"));
            m.put("chargeAmount", c0.get("chargeAmount"));
"""),
])

# ---- catalogue
sub('sangam-registry/src/main/java/org/sangam/registry/party/IdentifierTypeCatalogue.java', [
    (""" *       {@link #define}. Types under tenant {@code *} apply to every tenant unless the tenant defines the same key.</li>
""", """ *       {@link #define}. Types under tenant {@code *} apply to every tenant unless the tenant defines the same key.
 *       Types under {@link #jurisdictionScope} apply only to tenants whose profile resolves to that jurisdiction
 *       (ADR-D-20: national identifiers are jurisdiction data).</li>
"""),
    ("""    private volatile boolean allowUnconfigured;
""", """    private volatile boolean allowUnconfigured;
    private volatile java.util.function.Function<String, String> jurisdictionOf = t -> null;

    /** The seed key of a jurisdiction's types. */
    public static String jurisdictionScope(String jurisdiction) {
        return "jurisdiction:" + jurisdiction;
    }

    /** Sets how a tenant maps to its jurisdiction (null when it has none: jurisdiction seeds then do not apply). */
    public void jurisdictionResolver(java.util.function.Function<String, String> resolver) {
        this.jurisdictionOf = resolver == null ? t -> null : resolver;
    }

    private Map<String, IdentifierType> jurisdictionSeed(String tenant) {
        String j = jurisdictionOf.apply(tenant);
        return j == null ? null : seed.get(jurisdictionScope(j));
    }
"""),
    ("""        IdentifierType t = m == null ? null : m.get(typeKey);
        if (t == null) {
            Map<String, IdentifierType> all""", """        IdentifierType t = m == null ? null : m.get(typeKey);
        if (t == null) {
            Map<String, IdentifierType> scoped = jurisdictionSeed(tenant);
            t = scoped == null ? null : scoped.get(typeKey);
        }
        if (t == null) {
            Map<String, IdentifierType> all"""),
    ("""            out.putAll(all);
        }
""", """            out.putAll(all);
        }
        Map<String, IdentifierType> scoped = jurisdictionSeed(tenant);
        if (scoped != null) {
            out.putAll(scoped);
        }
"""),
])

# ---- loader
sub('sangam-server/src/main/java/org/sangam/server/bridge/RegistrySeedLoader.java', [
    ("""                        tenant = String.valueOf(map.get("tenant"));
                    }
""", """                        tenant = String.valueOf(map.get("tenant"));
                    }
                    if (map.get("jurisdiction") != null) {
                        tenant = org.sangam.registry.party.IdentifierTypeCatalogue.jurisdictionScope(
                                String.valueOf(map.get("jurisdiction")));
                    }
"""),
    (""" * ({@code tenant} defaults to {@code *} = every tenant).""",
     """ * ({@code tenant} defaults to {@code *} = every tenant; {@code jurisdiction} limits a file to tenants of that
 * jurisdiction)."""),
])
sub('sangam-server/src/main/java/org/sangam/server/config/RegistryWiring.java', [
    ("""    public ApplicationRunner sangamRegistrySeeds(RegistryModule module, Environment env) {
        return args -> {
""", """    public ApplicationRunner sangamRegistrySeeds(RegistryModule module, Environment env,
            ObjectProvider<org.sangam.config.profile.ProfileResolver> profiles) {
        return args -> {
            java.util.Map<String, String> jurisdictions = new java.util.concurrent.ConcurrentHashMap<>();
            module.parties().identifierTypes().jurisdictionResolver(tenant -> {
                String known = jurisdictions.get(tenant);
                org.sangam.config.profile.ProfileResolver p = profiles.getIfAvailable();
                if (known != null || p == null) {
                    return known;
                }
                try {
                    String j = p.resolve(tenant, java.time.LocalDate.now()).str("jurisdiction", null);
                    if (j != null) {
                        jurisdictions.put(tenant, j);
                    }
                    return j;
                } catch (RuntimeException noProfile) {
                    return null;
                }
            });
"""),
])

# ---- connector
sub('sangam-server/src/main/java/org/sangam/server/bridge/PackGapConnectors.java', [
    ("""    /**
     * {@code billing.credit} and {@code billing.revise}""", """    /**
     * {@code ledger.post}: posts an 835 remittance, claim by claim, on the claim receivable raised at submission
     * (account: ledger, line, period, holder = CLP01). Legs, each with its own stable reference:
     * paid = consume; CO / OA / PI = write-off; PR = transfer to holder {@code patient:<CLP01>}; status REVERSAL =
     * reverse the paid amount. A denied claim posts nothing (the denial is worked first). Anything that cannot be posted
     * cleanly (unmatched account, refused leg, unbalanced claim, CR group, reversal with adjustments) is returned in
     * {@code exceptions} / {@code exceptionCount} for the work queue (US-HLT-106). The claim balance rule is [U].
     */
    public static final class RemittancePosting implements SangamConnector {
        private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
        private final SangamConnector ledger;

        public RemittancePosting(SangamConnector ledger) {
            this.ledger = ledger;
        }

        @Override
        public ConnectorDescriptor descriptor() {
            return new ConnectorDescriptor("ledger.remittance", "in-process", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                    List.of("post"), ConnectorProfile.LIVE);
        }

        @Override
        public ConnectorResult invoke(ConnectorRequest r) {
            List<Map<String, Object>> claims = claims(r);
            if (claims == null) {
                return ConnectorResult.BusinessFailure.of("INPUT_REQUIRED", "eraClaims or patientControlNumber is required");
            }
            List<Map<String, Object>> exceptions = new java.util.ArrayList<>();
            int posted = 0;
            for (Map<String, Object> c : claims) {
                posted += post(r, c, exceptions);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("legsPosted", posted);
            out.put("exceptionCount", exceptions.size());
            out.put("exceptions", exceptions);
            return ConnectorResult.Success.of(out, r.operationId().toString());
        }

        private int post(ConnectorRequest r, Map<String, Object> c, List<Map<String, Object>> exceptions) {
            String pcn = String.valueOf(c.get("patientControlNumber"));
            String status = String.valueOf(c.getOrDefault("status", "PAID_PRIMARY"));
            if ("DENIED".equals(status)) {
                return 0;
            }
            if (Boolean.FALSE.equals(c.get("claimBalanced"))) {
                exceptions.add(exception(pcn, "claim", "CLAIM_NOT_BALANCED"));
                return 0;
            }
            if ("NOT_OUR_CLAIM".equals(status) || "PREDETERMINATION".equals(status) || "OTHER".equals(status)) {
                exceptions.add(exception(pcn, "claim", "STATUS_" + status));
                return 0;
            }
            java.math.BigDecimal paid = dec(c.get("paidAmount"));
            Map<?, ?> totals = c.get("adjustmentTotals") instanceof Map<?, ?> m ? m : Map.of();
            java.math.BigDecimal pr = totals.containsKey("PR") ? dec(totals.get("PR")) : dec(c.get("patientResponsibility"));
            java.math.BigDecimal writeOff = dec(totals.get("CO")).add(dec(totals.get("OA"))).add(dec(totals.get("PI")));
            int n = 0;
            if ("REVERSAL".equals(status)) {
                n += leg(r, pcn, "reverse", paid.abs(), Map.of(), exceptions);
                if (pr.signum() != 0 || writeOff.signum() != 0) {
                    exceptions.add(exception(pcn, "adjustments", "REVERSAL_ADJUSTMENTS_MANUAL"));
                }
                return n;
            }
            if (dec(totals.get("CR")).signum() != 0) {
                exceptions.add(exception(pcn, "CR", "CORRECTION_GROUP_MANUAL"));
            }
            n += leg(r, pcn, "consume", paid, Map.of("fromEncumbrance", "false"), exceptions);
            n += leg(r, pcn, "write_off", writeOff, Map.of(), exceptions);
            n += leg(r, pcn, "transfer", pr, Map.of("toHolder", "patient:" + pcn), exceptions);
            return n;
        }

        private int leg(ConnectorRequest r, String pcn, String op, java.math.BigDecimal amount, Map<String, Object> extra,
                        List<Map<String, Object>> exceptions) {
            if (amount.signum() == 0) {
                return 0;
            }
            Map<String, Object> in = new LinkedHashMap<>();
            for (String k : List.of("ledgerId", "line", "period", "actor")) {
                if (r.input().get(k) != null) {
                    in.put(k, r.input().get(k));
                }
            }
            in.put("holder", pcn);
            in.put("amount", amount.toPlainString());
            in.putAll(extra);
            java.util.UUID id = Hashes.uuidV5(r.operationId(), pcn + "|" + op);
            ConnectorResult res = ledger.invoke(new ConnectorRequest(r.tenantId(), id, r.attemptId(), r.attemptNumber(),
                    r.rootId(), r.businessKey(), op, in, r.deadline()));
            if (res instanceof ConnectorResult.Success) {
                return 1;
            }
            exceptions.add(exception(pcn, op, res instanceof ConnectorResult.BusinessFailure f ? f.code() : "LEG_NOT_POSTED"));
            return 0;
        }

        @SuppressWarnings("unchecked")
        private static List<Map<String, Object>> claims(ConnectorRequest r) {
            Object raw = r.input().get("eraClaims");
            try {
                if (raw instanceof String s && !s.isBlank()) {
                    return JSON.readValue(s, List.class);
                }
                if (raw instanceof List<?> l) {
                    return (List<Map<String, Object>>) l;
                }
            } catch (java.io.IOException e) {
                return null;
            }
            if (r.input().get("patientControlNumber") == null) {
                return null;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("patientControlNumber", r.input().get("patientControlNumber"));
            one.put("status", r.input().getOrDefault("eraClaimStatus", "PAID_PRIMARY"));
            one.put("paidAmount", r.input().get("eraPaidAmount"));
            one.put("patientResponsibility", r.input().get("eraPatientResponsibility"));
            return List.of(one);
        }

        private static Map<String, Object> exception(String pcn, String leg, String code) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("patientControlNumber", pcn);
            e.put("leg", leg);
            e.put("code", code);
            return e;
        }

        private static java.math.BigDecimal dec(Object v) {
            return v == null || v.toString().isBlank() ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(v.toString());
        }
    }

    /**
     * {@code billing.credit} and {@code billing.revise}"""),
])
s = open(R + 'sangam-server/src/main/java/org/sangam/server/config/PackGapWiring.java', encoding='utf-8', newline='').read()
a = s.index('                String ledger = env.getProperty("sangam.packs.ledger-post.ledger"')
b = s.index('            }\n        };', a) if '            }\n        };' in s[a:] else s.index('            }\r\n        };', a)
s = s[:a] + """                registry.register(new PackGapConnectors.RemittancePosting(new org.sangam.server.bridge.LedgerDefiningConnector(
                        new org.sangam.ledger.LedgerConnector(defs.ledger(), defs.periodResolver(), ConfigWiring.engineClock()), defs)));
                bindings.bind("ledger.post", "ledger.remittance", "post");
""" + s[b:]
open(R + 'sangam-server/src/main/java/org/sangam/server/config/PackGapWiring.java', 'w', encoding='utf-8', newline='').write(s)

# ---- claim pack
sub('sangam-packs/tools/packgen/sec_ushealth.py', [
    ("""'frequency=1;coderSeat=CODER;billerSeat=BILLER;denialSeat=DENIAL-ANALYST;rcmSeat=REV-CYCLE-MGR')""",
     """'frequency=1;coderSeat=CODER;billerSeat=BILLER;denialSeat=DENIAL-ANALYST;rcmSeat=REV-CYCLE-MGR;'
                        'ledgerId=RECEIVABLES;line=CLAIM;period=OPEN;eraBalanced=true;postExceptionCount=0')"""),
    ("""    s.flow(ag, w, None, 'accepted', default=True)
""", """    ra = s.setv('cl_receivableCfg', 'Receivable for the claim', values='holder=@patientControlNumber;amount=@totalCharge')
    al = s.conn('cl_receivable', 'Raise the claim receivable', 'ledger.allocate', 'allocate', 'submit',
                'ledgerId,line,period,holder,amount', 'recv', step='receivable')
    s.flow(ag, ra, None, 'accepted', default=True)
    s.seq(ra, al, w)
"""),
    ("""                  'patientControlNumber,eraPaidAmount,eraPatientResponsibility', 'post', step='posting')
    s.seq(remit, era, post)
    pg = s.xgw('cl_paidGw', 'Paid or denied?')
    s.flow(post, pg)
""", """                  'patientControlNumber,eraClaims,eraClaimStatus,eraPaidAmount,eraPatientResponsibility,ledgerId,line,period',
                  'post', step='posting')
    s.seq(remit, era, post)
    pg = s.xgw('cl_paidGw', 'Paid or denied?')
    qg = s.xgw('cl_postGw', 'Posted cleanly?')
    s.flow(post, qg)
    pq = s.user('cl_postException', 'Resolve the posting exception | Resolver la excepción de contabilización', '${billerSeat}',
                form='sec-ushealth-claim/post-exception', decision=None, fields=['remitSummary', 'remarks*'])
    s.flow(qg, pq, "postStatus == 'FAILED' || postExceptionCount > 0 || !eraBalanced", 'US-HLT-106 work queue')
    s.flow(qg, pg, None, 'clean', default=True)
    s.flow(pq, pg)
"""),
    ("""    pk.identifier_types = [
        {'typeKey': 'NPI'""", """    pk.identifier_types = [('US', [
        {'typeKey': 'NPI'"""),
    ("""         'matchWeight': 8, 'appliesTo': 'PERSON'}]
""", """         'matchWeight': 8, 'appliesTo': 'PERSON'}])]
"""),
])
sub('sangam-packs/tools/packgen/core.py', [
    ("""    # read by RegistrySeedLoader (packs/*/registry/*.json) for every tenant; administrators may override or retire them
    core.identifier_types = [
""", """    # read by RegistrySeedLoader (packs/*/registry/*.json); national identifiers apply only to that jurisdiction (ADR-D-20)
    core.identifier_types = [('IN', [
"""),
    ("""         'normalise': ['UPPER', 'STRIP_SPACES'], 'unique': True, 'matchWeight': 12, 'appliesTo': 'ORGANISATION'},
        {'typeKey': 'BANK_ACCOUNT'""", """         'normalise': ['UPPER', 'STRIP_SPACES'], 'unique': True, 'matchWeight': 12, 'appliesTo': 'ORGANISATION'},
        {'typeKey': 'BENEFICIARY_ID', 'label': 'Scheme beneficiary id (vault token)', 'normalise': ['TRIM'], 'unique': True,
         'matchWeight': 10}]), (None, [
        {'typeKey': 'BANK_ACCOUNT'"""),
    ("""         'sensitive': True, 'matchWeight': 4, 'appliesTo': 'PERSON'},
        {'typeKey': 'BENEFICIARY_ID', 'label': 'Scheme beneficiary id (vault token)', 'normalise': ['TRIM'], 'unique': True,
         'matchWeight': 10}]
""", """         'sensitive': True, 'matchWeight': 4, 'appliesTo': 'PERSON'}])]
"""),
])
sub('sangam-packs/tools/packgen/packs.py', [
    ("""            rpath = f'packs/{pk.dir}/registry/identifier-types.json'
            write(os.path.join(root, rpath), json.dumps({'identifierTypes': pk.identifier_types}, ensure_ascii=False,
                                                        indent=2) + '\\n')
            resources.append({'kind': 'OTHER', 'path': rpath})
""", """            for jur, types in pk.identifier_types:
                rpath = f'packs/{pk.dir}/registry/identifier-types{"-" + jur.lower() if jur else ""}.json'
                doc = ({'jurisdiction': jur} if jur else {})
                doc['identifierTypes'] = types
                write(os.path.join(root, rpath), json.dumps(doc, ensure_ascii=False, indent=2) + '\\n')
                resources.append({'kind': 'OTHER', 'path': rpath})
"""),
])
# ---- (a) credit held for the first bill, (b) head from the tariff
sub('sangam-packs/tools/packgen/sec_other.py', [
    ("""    cc = s.setv('uc_compCfg', 'Compensation credit inputs', values='amount=@compensationAmount',
                defaults='consumer=@applicantRef;head=ENERGY;cycle=first-bill')
""", """    # the credit needs a demand: held until the consumer's first bill names consumer, cycle and head
    cw = s.egw('uc_compWait', 'First bill or timeout')
    fb = s.catch_msg('uc_firstBill', 'First bill raised', 'sangam.util.firstBill')
    ft = s.catch_timer('uc_noBill', 'No bill in time', duration='P60D')
    s.flow(cw, fb)
    s.flow(cw, ft)
    cc = s.setv('uc_compCfg', 'Compensation credit inputs', values='amount=@compensationAmount')
    s.flow(fb, cc)
"""),
    ("""    s.flow(cm, cj)
""", """    s.flow(cm, cj)
    s.flow(ft, cm)
"""),
    ("""maybe(s, 'uc_compOpt', 'compensationDue', cc, cj, 'Delayed?')""", """maybe(s, 'uc_compOpt', 'compensationDue', cw, cj, 'Delayed?')"""),
])
sub('sangam-packs/tools/packgen/sec_more.py', [
    ("""tariffId=DEFAULT;cycle=@batchId;head=ENERGY')""", """tariffId=DEFAULT;cycle=@batchId;connectionCaseKey=')"""),
    ("""[('fixedCharge', 'Fixed charge', 'double'), ('energyRate', 'Energy rate per unit', 'double')],
           [([q('DOMESTIC'), '<= 100'], ['50', '3.5'], ''), ([q('DOMESTIC'), '-'], ['50', '5.5'], ''),
            (['-', '-'], ['200', '8'], 'commercial')])""",
     """[('fixedCharge', 'Fixed charge', 'double'), ('energyRate', 'Energy rate per unit', 'double'),
            ('head', 'Bill head (must equal the tariff line head)', 'string')],
           [([q('DOMESTIC'), '<= 100'], ['50', '3.5', q('ENERGY')], ''), ([q('DOMESTIC'), '-'], ['50', '5.5', q('ENERGY')], ''),
            (['-', '-'], ['200', '8', q('ENERGY')], 'commercial')])"""),
    ("""    s.seq(tr, cp, gb, du, w)
""", """    tb = s.svc('bl_tellConnection', 'First bill: release a held compensation credit', C['CORR'],
               {'messageName': 'sangam.util.firstBill', 'targetBusinessKey': '${connectionCaseKey}', 'optional': 'true',
                'variables': 'consumer,cycle,head'})
    s.seq(tr, cp, gb, tb, du, w)
"""),
    ("""    s.seq(sp, rc, rb)
""", """    s.seq(sp, rc, rb)
    rf = s.xgw('bl_revRes', 'Revised?')
    s.flow(rf, rv, "revStatus == 'FAILED'", 'refused: re-examine')
"""),
    ("""    s.flow(rb, du)
""", """    s.flow(rb, rf)
    s.flow(rf, du, None, 'revised', default=True)
"""),
])
sub('sangam-packs/src/test/java/org/sangam/packs/OperationIdAndSeedsTest.java', [
    ('''"packs/core/registry/identifier-types.json",
                "packs/sec-ushealth-claim/registry/identifier-types.json"''',
     '''"packs/core/registry/identifier-types.json", "packs/core/registry/identifier-types-in.json",
                "packs/sec-ushealth-claim/registry/identifier-types-us.json"'''),
])
# ---- profiles
sub('sangam-packs/tools/profilegen/main.py', [
    ("""dump('in/IN.yaml', IN)
""", """IN['jurisdiction'] = 'IN'
dump('in/IN.yaml', IN)
dump('us/US.yaml', {
    'profile': {'id': 'US', 'layer': 'jurisdiction', 'version': '2026.11.0', 'effectiveFrom': '2026-01-01', 'extends': ['INTL'],
                'name': {'en': 'United States: health revenue-cycle reference'},
                'source': 'spike/results/US-HEALTH-WORKFLOWS.md US-HLT-089, US-HLT-106'},
    'placeholders': [{'path': 'ledgers', 'reason': U + ' (the claim balance rule used in posting was not read from a public source)'}],
    'jurisdiction': 'US',
    'ledgers': [{'id': 'RECEIVABLES', 'kind': 'RECEIVABLE', 'mode': 'AUTHORITATIVE', 'currency': 'USD', 'stages': ['DEMAND'],
                 'availability': {'type': 'NO_YEAR'}}]})
"""),
])
print('ok')
