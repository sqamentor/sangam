# SPDX-License-Identifier: Apache-2.0
"""Pack container and writer: models, forms, seeds, manifest, index."""
import json
import os
import re

import check_results
import money
import powers
import prepare
import reason_codes
import releases
from lib import Proc, dmn as dmn_xml
from forms import L, OPT
from acceptance_runs import RUNS

ALL = []
MISSING = set()
# Second locale other than Hindi: code -> (field labels {id: text}, option labels {value: text}, case-summary label).
# A pack with second='<code>' writes {'en', '<code>'} labels; its name_hi and the '|' halves are in that language.
LOCALES = {}
# The platform default org hierarchy (top first). It must equal `security.orgLevels` of the INTL profile
# (tools/profilegen/central.py), which every shipped profile extends; a tenant may define its own levels in the
# onboarding request (security.orgLevels), and readiness then refuses seats whose level is not among them.
ORG_LEVELS = ['HEAD_OFFICE', 'REGIONAL_OFFICE', 'FIELD_OFFICE']
# SL-104 (ADR-D-20): the default integration connector (registry id, operation) of a pack connector, written into every
# pack.json connector entry with that id as `simulatorBinding` (the simulator connectors the integration module builds).
# Pack data, never product Java; a site's `sangam.packs.bindings.<packConnector>` configuration overrides it.
SIMULATOR_BINDINGS = {
    'pfms.payment': ('pfms-sim', 'pay'),
    'pfms.dbt': ('pfms-sim', 'pay'),
    'esign.sign': ('esign-sim', 'sign'),
    'digilocker.push': ('digilocker-sim', 'push'),
    'notify.sms': ('sms-dlt-sim', 'send'),
    'notify.email': ('smtp', 'send'),
    'payment.refund': ('payment-gateway-sim', 'createOrder'),
}
# SL-104: external-task topics a pack declares (`workers` in pack.json) when it uses the pack connector that serves
# them: (topic, operation, capability class). Registered by PackWorkers when the pack is discovered.
WORKERS_BY_CONNECTOR = {
    'pfms.payment': [('sangam.pfms.paymentFile', 'submitPaymentFile', 'c')],
}


class Pack:
    def __init__(self, id, dir, name_en, name_hi, domain, desc, covers, connectors=None, depends=('sangam.core',),
                 version='1.0.0', main=None, second='hi', currency=money.DEFAULT_CURRENCY):
        self.id, self.dir, self.name_en, self.name_hi = id, dir, name_en, name_hi
        self.currency = currency  # money is integer minor units of this ISO 4217 currency (money.py)
        self.money_vars = set()
        self.second = second
        self.domain, self.desc, self.covers = domain, desc, list(covers)
        self.connectors = list(connectors or [])
        self.depends = list(depends)
        self.version = releases.version_of(id, version)  # SL-54: a released version is immutable (releases.py)
        self.main = list(main or [])
        self.procs, self.dmns, self.cmmns, self.acceptance, self.overrides = [], [], [], [], []
        self.seed = None
        self.identifier_types = None
        self.placeholders = []
        # decision 385 (1): call activity id -> seat expression of its prepare task (default ${initiatorSeat})
        self.prepare_seats = {}
        ALL.append(self)

    def proc(self, pid, name, doc='', start=None, ttl=3650):
        if start is not None:
            start = dict({'packId': self.id, 'packVersion': self.version}, **start)
        p = Proc(pid, name, doc=doc, ttl=ttl, pack=self, version=self.version, start=start)
        self.procs.append(p)
        return p

    def dmn(self, did, name, hit, inputs, outputs, rules, doc=''):
        self._money_columns(inputs, outputs)
        self.dmns.append((did, dmn_xml(did, name, hit, inputs, outputs, rules, doc=doc, version=self.version,
                                       currency=self.currency)))

    def override(self, tenant, did, name, hit, inputs, outputs, rules, doc=''):
        """A tenant-specific DMN override (GP-37), deployed with tenantId = tenant."""
        self._money_columns(inputs, outputs)
        self.overrides.append((tenant, did, dmn_xml(did, name, hit, inputs, outputs, rules, doc=doc,
                                                    version=self.version, currency=self.currency)))

    def _money_columns(self, inputs, outputs):
        self.money_vars.update(expr for (_, expr, typ) in inputs if str(typ).lower() == 'money')
        self.money_vars.update(nm for (nm, _, typ) in outputs if str(typ).lower() == 'money')

    def cmmn(self, cid, xml):
        self.cmmns.append((cid, xml))

    def placeholder(self, decision, rule, reason):
        """A value shipped in a pack DMN that is not from a verified source. The readiness check reports it as an
        error for every tenant that enables the pack until the tenant deploys a confirmed override of `decision`."""
        self.placeholders.append(dict(decision=decision, rule=rule, reason=reason))

    def accept(self, id, title, refs, given, when, then, vars=None, state=None):
        vars = money.convert_vars(vars, f'{self.id} {id}', self.currency)  # authored in major units
        self.money_vars.update(k for k in vars if money.is_money(k))
        self.acceptance.append(dict(id=id, title=title, refs=refs, given=given, when=when, then=then,
                                    startVariables=vars, expectState=state))

    def seeds(self, offices, seats, holdings, roles):
        """Each seat names the org level of its office (seat['level']): an explicit value is kept, otherwise it is
        the office's depth in `offices` mapped onto ORG_LEVELS (the platform default hierarchy)."""
        parents = {o['id']: o.get('parent') or '' for o in offices}

        def depth(office, seen=()):
            if office not in parents:
                raise ValueError(f'pack {self.id}: seat office {office!r} is not declared in offices')
            if office in seen:
                raise ValueError(f'pack {self.id}: office cycle at {office!r}')
            p = parents[office]
            return 0 if not p else depth(p, seen + (office,)) + 1

        out = []
        for s in seats:
            if not s.get('level'):
                d = depth(s['office'])
                if d >= len(ORG_LEVELS):
                    raise ValueError(f'pack {self.id}: office {s["office"]!r} is deeper than ORG_LEVELS {ORG_LEVELS}')
                s = {k: v for k, v in s.items()}
                level = ORG_LEVELS[d]
                # keep key order stable: id, role, office, level, then the rest
                s = dict([(k, s[k]) for k in ('id', 'role', 'office') if k in s] + [('level', level)]
                         + [(k, v) for k, v in s.items() if k not in ('id', 'role', 'office')])
            out.append(s)
        self.seed = dict(offices=offices, seats=out, holdings=holdings, roles=roles)


def split_name(n):
    if '|' in n:
        en, hi = n.split('|', 1)
        return en.strip(), hi.strip()
    return n.strip(), None


def field_json(pack, fid, typ, req, label):
    """A form field. A money field (money.is_money) is type 'money': the value is integer minor units of the pack
    currency; the form shows and takes major units (value / 10^exponent)."""
    if money.is_money(fid):
        if typ not in ('decimal', 'integer', 'money', 'readonly'):
            raise ValueError(f'{pack.id}: money field {fid} has type {typ!r}')
        pack.money_vars.add(fid)
        return {'id': fid, 'type': 'readonly' if typ == 'readonly' else 'money', 'currency': pack.currency,
                'exponent': money.EXPONENT[pack.currency], 'required': req, 'label': label}
    return {'id': fid, 'type': typ, 'required': req, 'label': label}


def form_json_second(pack, f):
    """Form in English plus a non-Hindi second locale (pack.second)."""
    loc = pack.second
    labels, options, summary = LOCALES[loc]
    en, xx = split_name(f['name'])
    if xx is None:
        MISSING.add('title:' + en)
    fields = []
    for fid in f['fields']:
        req = fid.endswith('*')
        fid = fid.rstrip('*')
        if fid not in L or fid not in labels:
            MISSING.add(f'field:{fid}' if fid not in L else f'field-{loc}:{fid}')
            continue
        fields.append(field_json(pack, fid, L[fid][2], req, {'en': L[fid][0], loc: labels[fid]}))
    out = {'key': f['key'], 'version': pack.version, 'pack': pack.id, 'title': {'en': en, loc: xx or en},
           'caseSummary': {'source': 'materialFields', 'label': {'en': 'Case details', loc: summary}},
           'fields': fields}
    if f['decision']:
        var, values = f['decision']
        opts = []
        for v in values:
            if v not in OPT or v not in options:
                MISSING.add(f'opt:{v}' if v not in OPT else f'opt-{loc}:{v}')
                continue
            opts.append({'value': v, 'label': {'en': OPT[v][0], loc: options[v]}})
        out['decision'] = {'variable': var, 'required': True, 'options': opts}
    if f.get('doc'):
        out['help'] = f['doc']
    return out


def form_json(pack, f):
    if pack.second != 'hi':
        return form_json_second(pack, f)
    en, hi = split_name(f['name'])
    if hi is None:
        MISSING.add('title:' + en)
    fields = []
    for fid in f['fields']:
        req = fid.endswith('*')
        fid = fid.rstrip('*')
        if fid not in L:
            MISSING.add('field:' + fid)
            continue
        le, lh, typ = L[fid]
        fields.append(field_json(pack, fid, typ, req, {'en': le, 'hi': lh}))
    out = {'key': f['key'], 'version': pack.version, 'pack': pack.id, 'title': {'en': en, 'hi': hi or en},
           'caseSummary': {'source': 'materialFields', 'label': {'en': 'Case details', 'hi': 'प्रकरण का विवरण'}},
           'fields': fields}
    if f['decision']:
        var, values = f['decision']
        opts = []
        for v in values:
            if v not in OPT:
                MISSING.add('opt:' + v)
                continue
            opts.append({'value': v, 'label': {'en': OPT[v][0], 'hi': OPT[v][1]}})
        out['decision'] = {'variable': var, 'required': True, 'options': opts}
    if f.get('doc'):
        out['help'] = f['doc']
    return out


DSC_GATE = 'org.sangam.packs.core.DscSignatureGuardListener'
_BPMN_NS = {'b': 'http://www.omg.org/spec/BPMN/20100524/MODEL', 'o': 'http://operaton.org/schema/1.0/bpmn'}


def gate_pairs(xml, where):
    """Decision 269: the documentType-to-slot pairs of the DSC gates of one generated process (the create listener of
    every DSC gate, core.dsc_gate), read from its BPMN so no manifest repeats them by hand."""
    import xml.etree.ElementTree as ET
    pairs = []
    for tl in ET.fromstring(xml.encode('utf-8')).iter('{%s}taskListener' % _BPMN_NS['o']):
        if tl.get('class') != DSC_GATE or tl.get('event') != 'create':
            continue
        f = {fd.get('name'): (fd.findtext('o:string', default='', namespaces=_BPMN_NS) or '').strip()
             for fd in tl.findall('o:field', _BPMN_NS)}
        doc_type, slot = f.get('documentType', ''), f.get('documentSlot', '')
        if not doc_type or not slot:
            raise SystemExit(f'{where}: a DSC gate names no documentType or documentSlot')
        pairs.append((doc_type, slot))
    return pairs


def in_all_calls(xml):
    """The processes a generated process calls with in variables="all", the calls that carry its document slots."""
    import xml.etree.ElementTree as ET
    out = set()
    for c in ET.fromstring(xml.encode('utf-8')).iter('{%s}callActivity' % _BPMN_NS['b']):
        if any(i.get('variables') == 'all' for i in c.iter('{%s}in' % _BPMN_NS['o'])):
            out.add(c.get('calledElement'))
    return out


def reached_gate_pairs(pid, own, calls, seen=None):
    """Decisions 382 and 385 (1): the pairs of the DSC gates process pid holds itself and of every gate it reaches
    through call activities that pass in variables="all", transitively (only those carry the slot it fills; a callee
    reached otherwise fills its own slots, prepare.py). A called process that is not generated here stops generation."""
    seen = set() if seen is None else seen
    if pid in seen:
        return []
    seen.add(pid)
    if pid not in own:
        raise SystemExit(f'decision 382: process {pid} is called but not generated, so its DSC gates are unknown')
    out = list(own[pid])
    for c in sorted(calls.get(pid, ())):
        out += reached_gate_pairs(c, own, calls, seen)
    return out


def document_slots(pids, own, calls, pack_id):
    """Decisions 269 and 382: the documentType-to-slot pairs of a pack are those of its own DSC gates and of every DSC
    gate its processes call, so the document route fills the slot on the calling instance, before the call starts the
    gate's process (in variables="all" copies the slot). One type maps to one slot and one slot to one type; anything
    else stops generation."""
    pairs = {}
    for pid in pids:
        for doc_type, slot in reached_gate_pairs(pid, own, calls):
            if pairs.get(doc_type, slot) != slot:
                raise SystemExit(f'{pack_id}: document type {doc_type} maps to {pairs[doc_type]} and {slot}')
            pairs[doc_type] = slot
    if len(set(pairs.values())) != len(pairs):
        raise SystemExit(f'{pack_id}: one DSC slot serves two document types: {pairs}')
    return [{'documentType': t, 'slot': s} for t, s in sorted(pairs.items())]


def write_all(root):
    """root = src/main/resources. Writes packs/<dir>/..., META-INF/sangam/packs.idx; returns catalogue rows."""
    idx = ['# Sangam pack manifests (generated). One manifest path per line.']
    owner = {p.pid: pk for pk in ALL for p in pk.procs}
    core_tables = {did: xml for pk in ALL if pk.id == 'sangam.core' for did, xml in pk.dmns}
    # decision 382: every process is generated first, so a pack's manifest can name the DSC gates of the processes
    # it calls, whichever pack holds them
    xmls_of, resources_of, own_gates, calls_of = {}, {}, {}, {}
    # decisions 382 and 385 (1): a call into a DSC gate process gets its prepare task, then precedence is checked
    pack_of = {p.pid: pk for pk in ALL for p in pk.procs}
    prepare.apply([p for pk in ALL for p in pk.procs], lambda p: pack_of[p.pid].prepare_seats,
                  lambda p: pack_of[p.pid].dir)
    for pk in ALL:
        base = os.path.join(root, 'packs', pk.dir)
        os.makedirs(os.path.join(base, 'forms'), exist_ok=True)
        resources = resources_of[pk.id] = []
        xmls = xmls_of[pk.id] = {}
        for p in pk.procs:
            for n in all_user_names(p):
                if split_name(n)[1] is None:
                    pass
            for nm in p.forms:
                nm['name'] = nm['name']
            path = f'packs/{pk.dir}/{p.pid}.bpmn'
            # strip the Hindi half from BPMN element names (forms carry both languages)
            xml = p.xml()
            xml = strip_hindi(xml)
            xml = pin_cross_pack_calls(xml, pk, owner)
            check_reason_codes(path, xml)
            check_results.collect(path, xml)  # SL-115: checked after all packs (a default reaches called processes)
            write(os.path.join(root, path), xml)
            resources.append({'kind': 'BPMN', 'path': path})
            xmls[p.pid] = xml
            own_gates[p.pid] = gate_pairs(xml, path)
            calls_of[p.pid] = in_all_calls(xml)
    for pk in ALL:
        base = os.path.join(root, 'packs', pk.dir)
        resources = resources_of[pk.id]
        xmls = xmls_of[pk.id]
        for did, xml in pk.dmns:
            path = f'packs/{pk.dir}/{did}.dmn'
            write(os.path.join(root, path), xml)
            resources.append({'kind': 'DMN', 'path': path})
        for cid, xml in pk.cmmns:
            path = f'packs/{pk.dir}/{cid}.cmmn'
            check_reason_codes(path, xml)
            write(os.path.join(root, path), xml)
            resources.append({'kind': 'CMMN', 'path': path})
        seen = set()
        for p in pk.procs:
            for f in p.forms:
                if f['key'] in seen:
                    continue
                seen.add(f['key'])
                name = f['key'].split('/', 1)[1]
                path = f'packs/{pk.dir}/forms/{name}.form.json'
                write(os.path.join(root, path), json.dumps(form_json(pk, f), ensure_ascii=False, indent=2) + '\n')
                resources.append({'kind': 'FORM', 'path': path})
        overrides = []
        for tenant, did, xml in pk.overrides:
            path = f'packs/{pk.dir}/overrides/{tenant}/{did}.dmn'
            write(os.path.join(root, path), xml)
            overrides.append({'tenant': tenant, 'kind': 'DMN', 'path': path})
        seed_path = None
        if pk.seed:
            seed_path = f'packs/{pk.dir}/seed.json'
            write(os.path.join(root, seed_path), json.dumps(pk.seed, ensure_ascii=False, indent=2) + '\n')
            resources.append({'kind': 'SEED', 'path': seed_path})
        if pk.identifier_types:
            for jur, types in pk.identifier_types:
                rpath = f'packs/{pk.dir}/registry/identifier-types{"-" + jur.lower() if jur else ""}.json'
                doc = ({'jurisdiction': jur} if jur else {})
                doc['identifierTypes'] = types
                write(os.path.join(root, rpath), json.dumps(doc, ensure_ascii=False, indent=2) + '\n')
                resources.append({'kind': 'OTHER', 'path': rpath})
        conns = {}
        for c in pk.connectors:
            conns[c[0]] = {'id': c[0], 'capabilityClass': c[1], 'govTag': c[2], 'purpose': c[3]}
            if c[0] in SIMULATOR_BINDINGS:
                conn, op = SIMULATOR_BINDINGS[c[0]]
                conns[c[0]]['simulatorBinding'] = {'connector': conn, 'operation': op}
        workers = [{'topic': t, 'connector': cid, 'operation': op, 'capabilityClass': cls}
                   for cid in conns for (t, op, cls) in WORKERS_BY_CONNECTOR.get(cid, [])]
        mpath = f'packs/{pk.dir}/pack.json'
        # decision 193: the power-limit metadata of each core sanction call, deployed with the pack (the manifest is
        # then a resource of its own deployment, so a running case reads the declarations of the version it runs)
        power_block = powers.declare(pk, xmls, {**core_tables, **dict(pk.dmns)}, MISSING)
        if power_block:
            resources.append({'kind': 'OTHER', 'path': mpath})
        manifest = {
            'id': pk.id, 'version': pk.version, 'name': {'en': pk.name_en, pk.second: pk.name_hi}, 'domain': pk.domain,
            'description': pk.desc, 'mainProcesses': pk.main or [p.pid for p in pk.procs[:1]],
            'resources': resources, 'seed': seed_path, 'connectors': list(conns.values()), 'covers': pk.covers,
            'acceptance': pk.acceptance, 'dependsOn': pk.depends, 'tenantOverrides': overrides,
            'calls': sorted({c for p in pk.procs for c in p.calls}),
        }
        if workers:
            manifest['workers'] = workers
        slots = document_slots([p.pid for p in pk.procs], own_gates, calls_of, pk.id)
        if slots:
            manifest['documentSlots'] = slots  # decision 269: the server document route reads these, no server table
        if pk.placeholders:
            manifest['placeholders'] = pk.placeholders
        if power_block:
            manifest['currency'] = pk.currency  # the currency of the pack's money values and limit tables (ISO 4217)
            manifest['powers'] = power_block
        accepted = []
        for a in pk.acceptance:
            run = RUNS.get((pk.id, a['id']))
            if run is not None:
                a['run'] = money.convert_run(run, f'{pk.id} {a["id"]}', pk.currency)
            # money inside simulator directives, by the variable the data lands in, in the process the scenario
            # runs (its own pack's or the host process named by run.process); a copy, so a second write is the same
            host = [p for q in ALL for p in q.procs if run and p.pid == run.get('process')] or pk.procs
            prefixes = {}
            for p in host:
                for c, ps in p.conn_prefixes.items():
                    prefixes.setdefault(c, set()).update(ps)
            accepted.append(dict(a, startVariables=money.convert_sim_vars(
                a['startVariables'], prefixes, f'{pk.id} {a["id"]}', pk.currency)))
        manifest['acceptance'] = accepted
        if pk.money_vars:
            manifest['money'] = {'unit': 'MINOR', 'currency': pk.currency, 'exponent': money.EXPONENT[pk.currency],
                                 'variables': sorted(pk.money_vars)}
        check_reason_codes(mpath, json.dumps(manifest, ensure_ascii=False))   # acceptance-run answers
        write(os.path.join(root, mpath), json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
        idx.append(mpath)
    write(os.path.join(root, 'META-INF', 'sangam', 'packs.idx'), '\n'.join(idx) + '\n')
    # the money naming convention, for the build guard PackMoneyGuardTest (one source: money.py)
    write(os.path.join(root, 'META-INF', 'sangam', 'money.json'), json.dumps(money.convention(), indent=2) + '\n')
    # SL-55: the rejection reason-code catalogue, for ReasonCodeCatalogueTest (one source: reason_codes.py)
    write(os.path.join(root, 'META-INF', 'sangam', 'reason-codes.json'),
          json.dumps(reason_codes.catalogue(), ensure_ascii=False, indent=2) + '\n')
    # SL-115: no default pre-fills a check result unless reviewed (one source: check_results.py, CheckResultGuardTest)
    check_results.check(MISSING)
    write(os.path.join(root, 'META-INF', 'sangam', 'check-results.json'),
          json.dumps(check_results.catalogue(), ensure_ascii=False, indent=2) + '\n')


_REASONS = re.compile(r'rejectionReasonCodes(?:=|"\s*:\s*")([^;<"]*)')


def check_reason_codes(where, text):
    """SL-55: every reason code a generated file sets is in the catalogue (reason_codes.py); an unknown one stops the
    generation (main.py reports MISSING)."""
    for spec in _REASONS.findall(text):
        for code in reason_codes.unknown(spec.strip()):
            MISSING.add(f'reason code {code} ({where}; add it to reason_codes.CODES)')


def pin_cross_pack_calls(xml, pk, owner):
    """D-12: a call activity without a binding binds to its own deployment, and every pack is its own deployment, so a
    call into another pack (core blocks, a called pack) is pinned to the callee pack's version by versionTag (every
    generated process carries operaton:versionTag = its pack version). Calls inside the pack keep the deployment
    binding."""
    import re

    def pin(m):
        tag, called = m.group(0), m.group(1)
        callee = owner.get(called)
        if callee is None or callee is pk or 'calledElementBinding' in tag:
            return tag
        return tag.replace(f'calledElement="{called}"', f'calledElement="{called}" operaton:calledElementBinding="versionTag"'
                           f' operaton:calledElementVersionTag="{callee.version}"', 1)

    return re.sub(r'<callActivity\b[^>]*\bcalledElement="([^"$]+)"[^>]*>', pin, xml)


def all_user_names(p):
    return []


def strip_hindi(xml):
    import re
    return re.sub(r' \| [^"<]*"', '"', xml)


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write(text)
