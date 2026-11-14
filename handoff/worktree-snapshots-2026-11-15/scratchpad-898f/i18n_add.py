import json, sys, os
os.chdir(sys.argv[1])
add = {
 'en': {'act.setRemovalTime': 'Set removal time', 'fld.calculatedRemovalTime': 'Recalculate from history time to live', 'fld.absoluteRemovalTime': 'Fixed removal time', 'fld.clearedRemovalTime': 'Clear removal time', 'fld.hierarchical': 'Include called instances',
        'mod.op4.removalHint': 'Starts a batch over the historic instances of this definition. Choose one: recalculate, a fixed time, or clear.',
        'mod.op4.permissionsHint': 'Permissions are names for the chosen resource type, for example ["READ","UPDATE"], ["ALL"] or ["NONE"].',
        'mod.op4.varValueHint': 'Value is JSON: "text" for a String, 5 for a number, true or false, null. For Object, Xml or Json put objectTypeName and serializationDataFormat in value info.'},
 'hi': {'act.setRemovalTime': 'हटाने का समय तय करें', 'fld.calculatedRemovalTime': 'इतिहास अवधि से फिर गणना करें', 'fld.absoluteRemovalTime': 'निश्चित हटाने का समय', 'fld.clearedRemovalTime': 'हटाने का समय हटाएँ', 'fld.hierarchical': 'बुलाए गए इंस्टेंस भी शामिल करें',
        'mod.op4.removalHint': 'इस परिभाषा के ऐतिहासिक इंस्टेंस पर एक बैच शुरू होता है। एक चुनें: फिर गणना, निश्चित समय, या हटाना।',
        'mod.op4.permissionsHint': 'अनुमतियाँ चुने गए संसाधन प्रकार के नाम हैं, जैसे ["READ","UPDATE"], ["ALL"] या ["NONE"]।',
        'mod.op4.varValueHint': 'मान JSON में है: String के लिए "पाठ", संख्या के लिए 5, true या false, null। Object, Xml या Json के लिए value info में objectTypeName और serializationDataFormat दें।'}}
for lang, kv in add.items():
    p = f'src/i18n/locales/{lang}.json'
    raw = open(p, encoding='utf-8', newline='').read()
    nl = '\r\n' if '\r\n' in raw else '\n'
    d = json.loads(raw)
    for k, v in kv.items():
        cur = d
        parts = k.split('.')
        for x in parts[:-1]:
            cur = cur.setdefault(x, {})
        assert parts[-1] not in cur, k
        cur[parts[-1]] = v
    out = json.dumps(d, ensure_ascii=False, indent=2).replace('\n', nl)
    if raw.endswith(nl):
        out += nl
    open(p, 'w', encoding='utf-8', newline='').write(out)
print('ok')
