import json, sys
from collections import OrderedDict
ui = sys.argv[1]

EN = OrderedDict([
    ("assignments", "Assigned to this device"),
    ("claim", "These are mine: sync them"),
    ("discard", "Discard"),
    ("discardConfirm", "Discard this record for good"),
    ("error", OrderedDict([
        ("DEVICE_REVOKED", "This device is no longer allowed to sync. Ask your administrator."),
        ("NOT_REGISTERED", "Go online once to register this device before recording assignments."),
        ("OFFLINE_TOO_LONG", "This device has been offline too long. Go online and sync before recording more."),
        ("QUEUE_FULL", "The offline queue is full. Go online and sync before recording more."),
    ])),
    ("itemNotHere", "{{label}}: record this item in the field app."),
    ("keptHint", "The server did not accept these records. They stay on this device until you discard them."),
    ("keptTitle", "Records not accepted"),
    ("notDurable", "This browser cannot keep records offline: records are kept only until the page is closed."),
    ("revoked", "This device was revoked. Records on it are kept but no longer sync."),
    ("unclaimed", "{{n}} records from an earlier version of this app are on this device. Sync them only if you recorded them."),
    ("wiped", "This device was revoked and its offline records were erased."),
])
HI = OrderedDict([
    ("assignments", "इस डिवाइस को सौंपे गए कार्य"),
    ("claim", "ये मेरे हैं: इन्हें सिंक करें"),
    ("discard", "हटाएँ"),
    ("discardConfirm", "यह रिकॉर्ड स्थायी रूप से हटाएँ"),
    ("error", OrderedDict([
        ("DEVICE_REVOKED", "यह डिवाइस अब सिंक नहीं कर सकता। अपने व्यवस्थापक से संपर्क करें।"),
        ("NOT_REGISTERED", "कार्य दर्ज करने से पहले इस डिवाइस को पंजीकृत करने के लिए एक बार ऑनलाइन हों।"),
        ("OFFLINE_TOO_LONG", "यह डिवाइस बहुत समय से ऑफ़लाइन है। और दर्ज करने से पहले ऑनलाइन होकर सिंक करें।"),
        ("QUEUE_FULL", "ऑफ़लाइन कतार भर गई है। और दर्ज करने से पहले ऑनलाइन होकर सिंक करें।"),
    ])),
    ("itemNotHere", "{{label}}: यह मद फ़ील्ड ऐप में दर्ज करें।"),
    ("keptHint", "सर्वर ने ये रिकॉर्ड स्वीकार नहीं किए। जब तक आप इन्हें हटाते नहीं, ये इस डिवाइस पर रहेंगे।"),
    ("keptTitle", "अस्वीकृत रिकॉर्ड"),
    ("notDurable", "यह ब्राउज़र ऑफ़लाइन रिकॉर्ड नहीं रख सकता: रिकॉर्ड केवल पेज बंद होने तक रहेंगे।"),
    ("revoked", "यह डिवाइस निरस्त कर दिया गया। इस पर रखे रिकॉर्ड रहेंगे, पर सिंक नहीं होंगे।"),
    ("unclaimed", "इस ऐप के पुराने संस्करण के {{n}} रिकॉर्ड इस डिवाइस पर हैं। इन्हें तभी सिंक करें जब इन्हें आपने दर्ज किया हो।"),
    ("wiped", "यह डिवाइस निरस्त कर दिया गया और इसके ऑफ़लाइन रिकॉर्ड मिटा दिए गए।"),
])

def edit(path, add):
    raw = open(path, 'rb').read().decode('utf-8')
    crlf = '\r\n' in raw
    d = json.loads(raw, object_pairs_hook=OrderedDict)
    f = d['field']
    for k, v in add.items():
        assert k not in f, k
        f[k] = v
    d['field'] = OrderedDict(sorted(f.items(), key=lambda kv: kv[0]))
    out = json.dumps(d, ensure_ascii=False, indent=2) + '\n'
    if crlf:
        out = out.replace('\n', '\r\n')
    open(path, 'wb').write(out.encode('utf-8'))

edit(ui + '/src/i18n/locales/en.json', EN)
edit(ui + '/src/i18n/locales/hi.json', HI)

p = ui + '/scripts/i18n-dynamic.json'
raw = open(p, 'rb').read().decode('utf-8')
crlf = '\r\n' in raw
d = json.loads(raw, object_pairs_hook=OrderedDict)
assert 'field.error.' not in d
d['field.error.'] = ['NOT_REGISTERED', 'QUEUE_FULL', 'OFFLINE_TOO_LONG', 'DEVICE_REVOKED']
out = json.dumps(d, ensure_ascii=False, indent=2) + '\n'
if crlf:
    out = out.replace('\n', '\r\n')
open(p, 'wb').write(out.encode('utf-8'))
print('ok')
