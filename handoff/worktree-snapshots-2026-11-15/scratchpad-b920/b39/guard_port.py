"""Port of DomainNeutralityGuardTest.scanFile/lex/allowListed (sangam-core test) for one file, for M-011 evidence.
Currencies: ISO 4217 list from the JDK is approximated by every 3-letter uppercase literal reported for review;
the categories are reproduced exactly otherwise."""
import re
import sys

ZONE_ID = re.compile(r'^(Africa|America|Antarctica|Arctic|Asia|Atlantic|Australia|Europe|Indian|Pacific)/[A-Za-z0-9_+\-/]+$')
OFFSET_CALL = re.compile(r'ZoneOffset\.of(Hours|HoursMinutes|HoursMinutesSeconds|TotalSeconds)\(\s*-?\s*[1-9]')
OFFSET_LITERAL = re.compile(r'^[+-](0\d|1[0-8]):?[0-5]\d$')
PROPERTY_DEFAULT = re.compile(r'getProperty\(\s*"[^"]*"\s*,\s*"([A-Z]{2})"\s*\)')
LANGUAGE_TAG = re.compile(r'^[a-z]{2,3}[-_]([A-Z]{2})$')
TZ_ABBREV = re.compile(r'\b(IST|EST|PST|CST|EDT|PDT)\b')
SYSTEM_NAMES = re.compile(r'(?i)\b(aadhaar|gstin|upi|npci|pfms|ifms|digilocker|limbs|cctns|ecourts|umang|ssn|irs|medicare|medicaid)\b')


def allow_listed(rel):
    return '/connector/gov/' in rel or '/sim/' in rel or rel.startswith('sangam-connectors-health/')


def lex(text):
    out, lits, i, n, line = [], [], 0, len(text), 1
    while i < n:
        ch = text[i]
        if text.startswith('//', i):
            j = text.find('\n', i); j = n if j < 0 else j
            out.append(' ' * (j - i)); i = j
        elif text.startswith('/*', i):
            j = text.find('*/', i + 2); j = n if j < 0 else j + 2
            seg = text[i:j]; out.append(re.sub(r'[^\n]', ' ', seg)); line += seg.count('\n'); i = j
        elif text.startswith('"""', i):
            j = text.find('"""', i + 3); j = n if j < 0 else j + 3
            seg = text[i:j]; lits.append((seg[3:-3] if len(seg) >= 6 else '', line)); out.append(seg)
            line += seg.count('\n'); i = j
        elif ch == '"':
            j, buf = i + 1, []
            while j < n and text[j] != '"' and text[j] != '\n':
                if text[j] == '\\' and j + 1 < n:
                    buf.append(text[j:j + 2]); j += 2
                else:
                    buf.append(text[j]); j += 1
            j = min(j + 1, n); lits.append((''.join(buf), line)); out.append(text[i:j]); i = j
        elif ch == "'":
            j = i + 1
            while j < n and text[j] != "'" and text[j] != '\n':
                j += 2 if text[j] == '\\' else 1
            j = min(j + 1, n); out.append(text[i:j]); i = j
        else:
            if ch == '\n':
                line += 1
            out.append(ch); i += 1
    return ''.join(out), lits


def scan(rel, text):
    hits, review = [], []
    code, lits = lex(text)
    for v, ln in lits:
        if ZONE_ID.match(v):
            hits.append(('zone-id', ln, v))
        if OFFSET_LITERAL.match(v) and v.replace(':', '') not in ('+0000', '-0000'):
            hits.append(('fixed-offset', ln, v))
        if re.fullmatch(r'[A-Z]{3}', v) and v not in ('ALL', 'XXX', 'XTS'):
            review.append(('3-letter literal (currency check)', ln, v))
        m = LANGUAGE_TAG.match(v)
        if m:
            review.append(('language tag (country check)', ln, v))
    for no, l in enumerate(code.split('\n'), 1):
        if OFFSET_CALL.search(l):
            hits.append(('fixed-offset', no, l.strip()))
        for m in PROPERTY_DEFAULT.finditer(l):
            review.append(('property default', no, l.strip()))
        if TZ_ABBREV.search(l):
            hits.append(('tz-abbrev', no, l.strip()))
        if SYSTEM_NAMES.search(l):
            hits.append(('system-name', no, l.strip()))
    return hits, review, len(lits)


if __name__ == '__main__':
    root, rel = sys.argv[1], sys.argv[2]
    text = open(root + '/' + rel, encoding='utf-8').read()
    hits, review, nl = scan(rel, text)
    print('allowListed:', allow_listed(rel))
    print('literals:', nl, 'hits:', hits, 'to review:', review)
