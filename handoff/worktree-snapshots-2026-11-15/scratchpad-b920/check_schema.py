import json, sys, copy, glob, os
import yaml, jsonschema

chart = sys.argv[1]
schema = json.load(open(os.path.join(chart, 'values.schema.json'), encoding='utf-8'))
jsonschema.Draft7Validator.check_schema(schema)
V = jsonschema.Draft7Validator(schema)
defaults = yaml.safe_load(open(os.path.join(chart, 'values.yaml'), encoding='utf-8'))

def merge(base, over):
    out = copy.deepcopy(base)
    for k, v in (over or {}).items():
        if v is None:
            out.pop(k, None)
        elif isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = merge(out[k], v)
        else:
            out[k] = copy.deepcopy(v)
    return out

def errs(v):
    return [e.message for e in V.iter_errors(v)]

print('defaults', errs(defaults))
pg = yaml.safe_load(open(os.path.join(chart, 'examples/values-postgres.yaml')))
for f in sorted(glob.glob(os.path.join(chart, 'examples/values-*.yaml'))):
    o = yaml.safe_load(open(f))
    m = merge(defaults, pg) if 'airgap' in f else defaults
    print(os.path.basename(f), errs(merge(m, o)))

cases = {
 'private aws ok': ({'ingress': {'enabled': True, 'private': {'enabled': True, 'preset': 'aws'}}}, True),
 'private azure ok': ({'ingress': {'enabled': True, 'private': {'enabled': True, 'preset': 'azure'}}}, True),
 'private gcp ok': ({'ingress': {'enabled': True, 'private': {'enabled': True, 'preset': 'gcp'}}}, True),
 'private custom class ok': ({'ingress': {'enabled': True, 'private': {'enabled': True, 'className': 'nginx-internal'}}}, True),
 'private public className refused': ({'ingress': {'enabled': True, 'className': 'nginx', 'private': {'enabled': True, 'className': 'nginx-internal'}}}, False),
 'private no class refused': ({'ingress': {'enabled': True, 'private': {'enabled': True}}}, False),
 'private gce class refused': ({'ingress': {'enabled': True, 'private': {'enabled': True, 'className': 'gce'}}}, False),
 'private gcp with className refused': ({'ingress': {'enabled': True, 'private': {'enabled': True, 'preset': 'gcp', 'className': 'gce-internal'}}}, False),
 'private ingress disabled refused': ({'ingress': {'private': {'enabled': True, 'preset': 'aws'}}}, False),
 'private internet-facing annotation refused': ({'ingress': {'enabled': True, 'annotations': {'alb.ingress.kubernetes.io/scheme': 'internet-facing'}, 'private': {'enabled': True, 'preset': 'aws'}}}, False),
 'private use-private-ip false refused': ({'ingress': {'enabled': True, 'annotations': {'appgw.ingress.kubernetes.io/use-private-ip': 'false'}, 'private': {'enabled': True, 'preset': 'azure'}}}, False),
 'private gce annotation refused': ({'ingress': {'enabled': True, 'annotations': {'kubernetes.io/ingress.class': 'gce'}, 'private': {'enabled': True, 'className': 'x-internal'}}}, False),
 'preset tampered aws refused': ({'ingress': {'private': {'presets': {'aws': {'annotations': {'alb.ingress.kubernetes.io/scheme': 'internet-facing'}}}}}}, False),
 'preset tampered gcp refused': ({'ingress': {'private': {'presets': {'gcp': {'annotations': {'kubernetes.io/ingress.class': 'gce'}}}}}}, False),
 'preset tampered azure refused': ({'ingress': {'private': {'presets': {'azure': {'annotations': {'appgw.ingress.kubernetes.io/use-private-ip': 'false'}}}}}}, False),
 'unknown preset refused': ({'ingress': {'enabled': True, 'private': {'enabled': True, 'preset': 'oci'}}}, False),
 'public ingress className still ok': ({'ingress': {'enabled': True, 'className': 'nginx'}}, True),
 'non-string annotation refused': ({'ingress': {'annotations': {'a': True}}}, False),
 'prometheusRule enabled ok': ({'metrics': {'prometheusRule': {'enabled': True, 'labels': {'release': 'kps'}}}}, True),
 'prometheusRule bad threshold refused': ({'metrics': {'prometheusRule': {'thresholds': {'rootLockWaitSeconds': 0}}}}, False),
 'prometheusRule unknown key refused': ({'metrics': {'prometheusRule': {'severity': 'x'}}}, False),
 'jobExecutor bool kept': ({'server': {'jobExecutor': {'exclusiveOverProcessHierarchies': 'yes'}}}, False),
}
bad = 0
for name, (over, ok) in cases.items():
    e = errs(merge(defaults, over))
    good = (not e) == ok
    bad += not good
    print(('PASS ' if good else 'FAIL ') + name, e[:2])
print('failures', bad)
