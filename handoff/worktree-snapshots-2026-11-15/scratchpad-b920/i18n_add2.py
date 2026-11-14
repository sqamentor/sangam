import json, sys
from collections import OrderedDict
ui = sys.argv[1]
EN = {
    "logoutAnyway": "Log out anyway",
    "logoutKept": "They stay encrypted on this device and are sent only when you sign in again.",
    "logoutPromptLabel": "Unsent field records",
    "logoutUnsent": "{{n}} records not yet sent",
}
HI = {
    "logoutAnyway": "फिर भी लॉग आउट करें",
    "logoutKept": "ये इस डिवाइस पर एन्क्रिप्टेड रहेंगे और केवल आपके दोबारा साइन इन करने पर भेजे जाएँगे।",
    "logoutPromptLabel": "न भेजे गए फ़ील्ड रिकॉर्ड",
    "logoutUnsent": "{{n}} रिकॉर्ड अभी तक नहीं भेजे गए",
}
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
print('ok')
