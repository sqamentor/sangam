# SPDX-License-Identifier: Apache-2.0
"""Pack container and writer: models, forms, seeds, manifest, index."""
import json
import os

import money
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
        self.version = version
        self.main = list(main or [])
        self.procs, self.dmns, self.cmmns, self.acceptance, self.overrides = [], [], [], [], []
        self.seed = None
        self.identifier_types = None
        self.placeholders = []
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


def write_all(root):
    """root = src/main/resources. Writes packs/<dir>/..., META-INF/sangam/packs.idx; returns catalogue rows."""
    idx = ['# Sangam pack manifests (generated). One manifest path per line.']
    for pk in ALL:
        base = os.path.join(root, 'packs', pk.dir)
        os.makedirs(os.path.join(base, 'forms'), exist_ok=True)
        resources = []
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
            write(os.path.join(root, path), xml)
            resources.append({'kind': 'BPMN', 'path': path})
        for did, xml in pk.dmns:
            path = f'packs/{pk.dir}/{did}.dmn'
            write(os.path.join(root, path), xml)
            resources.append({'kind': 'DMN', 'path': path})
        for cid, xml in pk.cmmns:
            path = f'packs/{pk.dir}/{cid}.cmmn'
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
        manifest = {
            'id': pk.id, 'version': pk.version, 'name': {'en': pk.name_en, pk.second: pk.name_hi}, 'domain': pk.domain,
            'description': pk.desc, 'mainProcesses': pk.main or [p.pid for p in pk.procs[:1]],
            'resources': resources, 'seed': seed_path, 'connectors': list(conns.values()), 'covers': pk.covers,
            'acceptance': pk.acceptance, 'dependsOn': pk.depends, 'tenantOverrides': overrides,
            'calls': sorted({c for p in pk.procs for c in p.calls}),
        }
        if pk.placeholders:
            manifest['placeholders'] = pk.placeholders
        for a in pk.acceptance:
            if (pk.id, a['id']) in RUNS:
                a['run'] = money.convert_run(RUNS[(pk.id, a['id'])], f'{pk.id} {a["id"]}', pk.currency)
        if pk.money_vars:
            manifest['money'] = {'unit': 'MINOR', 'currency': pk.currency, 'exponent': money.EXPONENT[pk.currency],
                                 'variables': sorted(pk.money_vars)}
        mpath = f'packs/{pk.dir}/pack.json'
        write(os.path.join(root, mpath), json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
        idx.append(mpath)
    write(os.path.join(root, 'META-INF', 'sangam', 'packs.idx'), '\n'.join(idx) + '\n')
    # the money naming convention, for the build guard PackMoneyGuardTest (one source: money.py)
    write(os.path.join(root, 'META-INF', 'sangam', 'money.json'), json.dumps(money.convention(), indent=2) + '\n')


def all_user_names(p):
    return []


def strip_hindi(xml):
    import re
    return re.sub(r' \| [^"<]*"', '"', xml)


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write(text)
