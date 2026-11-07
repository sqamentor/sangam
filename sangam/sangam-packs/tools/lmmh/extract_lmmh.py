# SPDX-License-Identifier: Apache-2.0
"""Extract the official List of Major and Minor Heads of Account (LMMH, CGA) into a flat CSV.

Usage (run with python -I; the PDF is untrusted data and is only read, never executed):

    python -I extract_lmmh.py --pdf PATH/LMMH-CSnnnn.pdf --slip 1127 --slip-date 2026-10-09 \
        [--sha256 EXPECTED] [--pdftotext PATH] [--out DIR]

Text comes from xpdf/poppler ``pdftotext -simple -enc UTF-8`` (column-preserving, one PDF line per text line).
Two parts of the PDF are parsed:

* the table of contents (pages before "GENERAL DIRECTIONS"): every major head with its grouping
  (division, part, sector, sub-sector, group);
* the detailed list (pages with the "MAJOR / SUB-MAJOR HEADS   MINOR HEADS" header): sub-major heads (2 digits) and
  minor heads (3 digits) under each major head. Notes below the lists are skipped.
* the "Illustrative List of Standard Minor Head Codes" (General Directions).

Outputs in --out (default: ./data next to this script):
  lmmh-heads.csv      level,major,subMajor,code,name,footnotes,page,division,part,sector,subSector,group,nature
  lmmh-standard-minors.csv  code,name,directions,page
  lmmh-report.json    counts, anomalies, validation results, the 30-row spot check sample
"""
import argparse
import csv
import difflib
import hashlib
import json
import os
import random
import re
import shutil
import subprocess
import sys
import unicodedata

HDR = re.compile(r'MAJOR\s*/?\s*SUB[\s-]*MAJOR\s+[HB]EADS')
NOTE = re.compile(r'^\s{0,12}(Notes?|NOTES?)\b\s*[:\-.–]?')
SEG = re.compile(r'\S+(?: {1,2}\S+)*')
FOOT = re.compile(r'(?:[\s,]*\(\s*(?:\d+|l)(?:\s*,\s*\d+)*\s*\))+\s*$')
ROMAN = {'i', 'ii', 'iii', 'iv', 'v', 'vi', 'vii', 'viii', 'ix', 'x'}


def norm(s):
    s = unicodedata.normalize('NFKC', s)
    s = s.replace('–', '-').replace('—', '-').replace('‘', "'").replace('’', "'")
    s = s.replace('“', '"').replace('”', '"').replace('�', '-')
    s = re.sub(r'\s+', ' ', s).strip()
    s = re.sub(r'\s*-\s*$', '-', s)
    return s


def clean_name(s):
    """Returns (name, footnote markers). Footnote markers such as (1), (2)(13) at the end are split off."""
    s = norm(s)
    feet = []
    m = FOOT.search(s)
    while m and m.start() > 0:
        feet = re.findall(r'\d+', m.group(0)) + feet
        s = s[:m.start()].rstrip(' ,')
        m = FOOT.search(s)
    s = re.sub(r'(\w)\(\d+\)', r'\1', s)  # "Labour(1)" -> "Labour"
    s = s.strip()
    if s.count("'") % 2 or (s[:1] == "'" and s[-1:] == "'"):
        s = s.strip("'").strip()  # stray quotes left by the correction slips
    return s, ','.join(feet)


def join(a, b):
    a = a.rstrip()
    if a.endswith('-') and b[:1].islower():
        return a + b  # "Un-" + "apportioned" keeps the hyphen, which is how the list writes it
    return a + ' ' + b


def pdf_text(pdf, exe):
    out = subprocess.run([exe, '-simple', '-enc', 'UTF-8', '-q', pdf, '-'], capture_output=True, check=True)
    return out.stdout.decode('utf-8', 'replace').split('\f')


# ---------------------------------------------------------------------------------------------- table of contents
def parse_toc(pages):
    end = next(i for i, p in enumerate(pages) if i > 0 and 'GENERAL DIRECTIONS' in p)
    majors, anomalies = [], []
    ctx = {'division': '', 'part': '', 'sector': '', 'subSector': '', 'group': ''}
    last_heading = None
    last = None
    for pno in range(1, end):
        prev_blank = True
        for line in pages[pno].splitlines():
            if not line.strip():
                prev_blank = True
                continue
            ind = len(line) - len(line.lstrip())
            txt = norm(line)
            m = re.match(r'^(\d{3,4})\.?\s+(.*)$', txt)
            if m and ind <= 10:
                code, name = m.group(1), m.group(2)
                if len(code) == 3:
                    anomalies.append({'page': pno + 1, 'issue': 'TOC code has 3 digits', 'text': txt})
                last = dict(code=code, name=name, tocPage=pno + 1, **ctx)
                majors.append(last)
                last_heading = None
                prev_blank = False
                continue
            if ind <= 10 and last is not None and not txt.isupper() and not re.match(r'^(\([a-z]+\)|[A-N]\s*[.\-])\s*\S', txt):
                last['name'] = join(last['name'], txt)  # wrapped major title
                prev_blank = False
                continue
            if txt.startswith('(Correction') or txt in ('TABLE OF CONTENTS', 'General Directions'):
                continue
            # a heading
            up = re.sub(r'[^A-Za-z]', '', txt).isupper()
            if up and ('FUND' in txt or 'PUBLIC ACCOUNT' in txt):
                ctx.update(division=txt, part='', sector='', subSector='', group='')
                if txt in ('CONTINGENCY FUND', 'PUBLIC ACCOUNT'):
                    ctx['part'] = txt
                last_heading = 'division'
            elif up and 'HEADS' in txt:
                ctx.update(part=txt, sector='', subSector='', group='')
                last_heading = 'part'
            elif re.match(r'^[A-N]\s*[.\-]\s*\S', txt):
                ctx.update(sector=txt, subSector='', group='')
                last_heading = 'sector'
            elif re.match(r'^\(([a-z]+)\)', txt):
                tok = re.match(r'^\(([a-z]+)\)', txt).group(1)
                letter = ctx['subSector'][1:2] if ctx['subSector'] else ''
                is_roman = tok in ROMAN and not (tok == 'i' and letter == 'h')
                if is_roman and ctx['subSector']:
                    ctx['group'] = txt
                    last_heading = 'group'
                else:
                    ctx.update(subSector=txt, group='')
                    last_heading = 'subSector'
            elif last_heading and not prev_blank or last_heading in ('sector', 'subSector', 'group'):
                ctx[last_heading] = join(ctx[last_heading], txt)  # wrapped heading
            else:
                anomalies.append({'page': pno + 1, 'issue': 'unclassified TOC line', 'text': txt})
            prev_blank = False
    for mj in majors:
        mj['name'], mj['footnotes'] = clean_name(mj['name'])
        if not mj['name'].endswith('etc.'):
            mj['name'] = re.sub(r'\.$', '', mj['name']).strip()
    return majors, anomalies, end


# ---------------------------------------------------------------------------------------------- standard minors
def parse_standard(pages, start, end):
    rows, cur = [], None
    for pno in range(start, end):
        p = pages[pno]
        if 'Standard Minor Head' not in p and cur is None:
            continue
        for line in p.splitlines():
            if not line.strip():
                cur = None if cur and rows and rows[-1] is cur and False else cur
                continue
            if re.match(r'^\s*(Note|\*Strike|Sub Head and Below)', line):
                cur = None
                if line.strip().startswith('Sub Head'):
                    return rows
                continue
            if re.search(r'Illustrative List|\(Contd|^\s*(Standard|3-digit|Code)|Directions\)\s*$', line):
                cur = None
                continue
            line = re.sub(r'^\s*onwards\s+', ' ' * 14, line)
            m = re.match(r'^\s{0,8}(\d{3}(?:/\d{3})?)\s{2,}(.*?)(?:\s{2,}([\d.,\s]+))?\s*$', line)
            if m:
                cur = {'code': m.group(1), 'name': m.group(2), 'directions': (m.group(3) or '').strip(), 'page': pno + 1}
                rows.append(cur)
            elif cur is not None and len(line) - len(line.lstrip()) >= 8:
                m2 = re.match(r'^\s*(.*?)(?:\s{2,}([\d.,\s]+))?\s*$', line)
                cur['name'] = join(cur['name'], m2.group(1))
                if m2.group(2):
                    cur['directions'] = join(cur['directions'], m2.group(2).strip()).strip()
    return rows


# ---------------------------------------------------------------------------------------------- detailed list
def segments(line):
    return [(m.start(), m.group(0)) for m in SEG.finditer(line)]


def parse_body(pages, start, toc_codes):
    """Returns heads (list of dicts) and anomalies.

    Each line is cut into segments (runs separated by 3+ spaces). A segment left of the page's minor-code column is in
    the major / sub-major zone, the rest is the minor zone. A code segment opens a head; a text segment continues the
    head that the same zone extended on the line directly above (wrapped names); other text in the minor zone is a
    rule for opening minor heads (for example "Each aided Institute will be a separate Minor Head")."""
    heads, anomalies = [], []
    major = sub = None
    mode = 'skip'
    tmpl = {}
    right_prev = None
    for pno in range(start, len(pages)):
        lines = pages[pno].splitlines()
        mcols = [len(l) - len(l.lstrip()) for l in lines if re.match(r'^\s*\d{3}\s+\S', l)]
        mincol = (min(mcols) if mcols else 20) - 2
        left_prev = None
        for line in lines:
            if not line.strip():
                left_prev = None
                if right_prev is not None and right_prev.get('level') != 'minor':
                    right_prev = None
                continue  # a wrapped minor name may continue after a blank line
            if HDR.search(line):
                mode = 'list'
                left_prev = right_prev = None
                continue
            if NOTE.match(line):
                mode = 'notes'
                continue
            line = re.sub(r'^(\s*)Major Head\s*:\s*', lambda m: m.group(1), line)
            g = re.match(r'^(\s*\d{4}\s.*\(\d+\))(\d{3})([A-Z].*)$', line)
            if g:  # major title and first minor glued together
                line = g.group(1) + ' ' * max(3, mincol + 2 - len(g.group(1))) + g.group(2) + '   ' + g.group(3)
                anomalies.append({'page': pno + 1, 'issue': 'repaired: minor glued to major title', 'text': norm(line)})
            g = re.match(r'^\s*(\S.*?)\s+(\S*[a-z]\d\S*)\s+(\1\s.*)$', re.sub(r'\s+', ' ', line))
            if g:  # code overprinted on a duplicate of the name
                code = ''.join(ch for ch in g.group(2) if ch.isdigit())
                line = ' ' * (mincol + 2) + code + '   ' + g.group(3)
                anomalies.append({'page': pno + 1, 'issue': 'repaired: code overprinted on name', 'text': norm(line)})
            segs = segments(line)
            if segs[0][0] < mincol:  # a minor code that touches the left column text: open a gap before it
                g = next((x for x in re.finditer(r'(?<![\d.,/-])(\d{3})(?=\s+\S)', line) if x.start() >= mincol - 8), None)
                if g and not re.match(r'^\s*\d{3}\s', line):
                    line = line[:g.start()].rstrip() + ' ' * 3 + ' ' * max(0, mincol - len(line[:g.start()].rstrip()) - 3) + line[g.start():]
                    segs = segments(line)
            col0, t0 = segs[0]
            mm = re.match(r'^(\d{4})\.?(?:\s+(.*))?$', t0)
            is_major = bool(mm and col0 <= 8 and mm.group(1) in toc_codes and (len(segs) > 1 or mm.group(2)))
            if is_major:
                mode = 'list'
            if mode != 'list':
                continue
            if re.match(r'^\s{0,10}\(\d+\)\s', line) and not is_major:
                mode = 'notes'  # note paragraphs without a "Note:" title
                continue
            items = []
            i = 0
            while i < len(segs):
                c, t = segs[i]
                if re.fullmatch(r'\d{2,4}\.?', t) and i + 1 < len(segs) and not re.fullmatch(r'\d{2,4}\.?', segs[i + 1][1]):
                    items.append((c, t.rstrip('.') + ' ' + segs[i + 1][1]))
                    i += 2
                    continue
                m = re.match(r'^(.*\S)\s(\d{3})$', t)
                if m and c < mincol and i + 1 < len(segs) and segs[i + 1][0] >= mincol:
                    # "03Banking Cash Transaction 101        Ordinary Collections": the minor code joined the left text
                    items.append((c, m.group(1)))
                    items.append((c + len(m.group(1)) + 1, m.group(2) + ' ' + segs[i + 1][1]))
                    i += 2
                    continue
                items.append((c, t))
                i += 1
            left_cur = right_cur = None
            for c, t in items:
                m4 = re.match(r'^(\d{4})\.?\s+(.+)$', t)
                m3 = re.match(r'^(\d{3})\s+(\S.*)$', t)
                m2 = re.match(r'^(\d{2})(?:\s*[-.]\s*|\s+|(?=[A-Z]))([A-Za-z(\'"].*)$', t)
                if m4 and c <= 8 and m4.group(1) in toc_codes:
                    major = {'level': 'major', 'major': m4.group(1), 'subMajor': '', 'code': m4.group(1),
                             'raw': m4.group(2), 'page': pno + 1, 'rule': ''}
                    heads.append(major)
                    sub = None
                    left_cur, right_cur = major, None
                elif major is None:
                    continue
                elif m3 and c >= mincol:
                    e = {'level': 'minor', 'major': major['major'], 'subMajor': sub['code'] if sub else '00',
                         'code': m3.group(1), 'raw': m3.group(2), 'page': pno + 1, 'rule': ''}
                    heads.append(e)
                    right_cur = e
                elif m2 and c < mincol:
                    sub = {'level': 'subMajor', 'major': major['major'], 'subMajor': m2.group(1), 'code': m2.group(1),
                           'raw': m2.group(2), 'page': pno + 1, 'rule': ''}
                    heads.append(sub)
                    left_cur = sub
                elif c < mincol:
                    target = left_cur or left_prev
                    each = re.match(r'^Each\b', t)
                    if re.match(r'^(\([a-z]+\)|[A-N]\s*[.-])(\s|$)', t) or t.isupper():
                        continue  # a sector heading between lists
                    if target is None and not each and right_prev is not None and right_prev.get('level') == 'minor':
                        right_prev['raw'] = join(right_prev['raw'], t)  # wrapped minor name printed further left
                        right_cur = right_prev
                    elif target is not None and not each:
                        if target.get('template'):
                            target['template'] = join(target['template'], norm(t))
                        else:
                            target['raw'] = join(target['raw'], t)
                        left_cur = target
                    elif each or re.search(r'sub[\s-]*major', t, re.I) or re.search(r'will be a', t, re.I):
                        n = tmpl.get(major['major'], 0) + 1
                        tmpl[major['major']] = n
                        sub = {'level': 'template', 'major': major['major'], 'subMajor': '*%d' % n, 'code': '*%d' % n,
                               'raw': '', 'template': norm(t), 'page': pno + 1, 'rule': ''}
                        heads.append(sub)
                        left_cur = sub
                    else:
                        anomalies.append({'page': pno + 1, 'issue': 'unplaced text, major/sub-major column', 'text': norm(t)})
                else:
                    target = right_cur or right_prev
                    isrule = re.search(r'will be an? |^\(?Each\b|^\(?A (separate )?minor head|^\(?Minor heads', t, re.I)
                    if isrule and (target is None or target.get('level') == 'minor') and not (target or {}).get('ruleOpen') and not t.startswith('('):
                        holder = sub or major
                        holder['rule'] = join(holder['rule'], t).strip()
                        right_cur = holder
                    elif target is not None and target.get('level') == 'minor':
                        if target.get('ruleOpen') or re.match(r'^\((Each|A separate|Minor heads)\b', t):
                            target['rule'] = join(target['rule'], t).strip()
                            target['ruleOpen'] = not t.endswith(')')
                        else:
                            target['raw'] = join(target['raw'], t)
                        right_cur = target
                    elif re.match(r'^(\([a-z]+\)|[A-N]\s*[.-])\s', t) and target is None:
                        continue  # a sector heading above the next list
                    else:
                        holder = target if target is not None else (sub or major)
                        holder['rule'] = join(holder['rule'], t).strip()
                        right_cur = holder
                        if not re.search(r'minor|each|separate|name of', holder['rule'], re.I):
                            anomalies.append({'page': pno + 1, 'issue': 'text without code in minor column', 'text': norm(t)})
            left_prev, right_prev = left_cur, right_cur
    for h in heads:
        h['name'], h['footnotes'] = clean_name(h.pop('raw'))
        h['rule'] = norm(h['rule'])
        if h['level'] == 'template':
            h['name'] = h['template']
    return heads, anomalies


# ---------------------------------------------------------------------------------------------- main
NATURE = [(('0', '1'), 'RECEIPT', 'REVENUE'), (('2', '3'), 'EXPENDITURE', 'REVENUE'), (('4',), 'CAPITAL', 'CAPITAL'),
          (('5',), 'EXPENDITURE', 'CAPITAL'), (('6', '7'), 'LOANS', 'CAPITAL')]


def nature(code, part):
    p = part.upper()
    if code == '8000':
        return 'CONTINGENCY_FUND'
    if code[0] == '8':
        return 'PUBLIC_ACCOUNT'
    if code == '4000' or 'RECEIPT' in p:
        return 'RECEIPT'
    if code[0] in '67':
        return 'PUBLIC_DEBT' if code[:2] == '60' else 'LOANS'
    return 'EXPENDITURE'


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--pdf', required=True)
    ap.add_argument('--slip', required=True, help='last correction slip incorporated, e.g. 1127')
    ap.add_argument('--slip-date', required=True, help='date of that slip, YYYY-MM-DD')
    ap.add_argument('--sha256', help='expected SHA-256 of the PDF; extraction stops on mismatch')
    ap.add_argument('--pdftotext', default=shutil.which('pdftotext') or 'pdftotext')
    ap.add_argument('--out', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), 'data'))
    ap.add_argument('--seed', type=int, default=1127, help='random seed of the spot-check sample')
    a = ap.parse_args(argv)

    with open(a.pdf, 'rb') as f:
        blob = f.read()
    sha = hashlib.sha256(blob).hexdigest().upper()
    if a.sha256 and a.sha256.upper() != sha:
        sys.exit('SHA-256 mismatch: %s' % sha)
    pages = pdf_text(a.pdf, a.pdftotext)
    if sum(len(p.strip()) for p in pages) < 1000:
        sys.exit('no text layer: the PDF looks image-only; OCR is out of scope')

    toc, toc_anom, gd = parse_toc(pages)
    body_start = next(i for i in range(gd, len(pages)) if HDR.search(pages[i]))
    std = parse_standard(pages, gd, body_start)
    for s in std:
        s['name'], _ = clean_name(s['name'])
    toc_codes = {m['code'] for m in toc if len(m['code']) == 4}
    # a 3-digit TOC code is a typo for a 4-digit code; resolve it from the body by name
    heads, body_anom = parse_body(pages, body_start, toc_codes | {'6' + m['code'] for m in toc if len(m['code']) == 3})
    body_major = {}
    for h in heads:
        if h['level'] == 'major':
            body_major.setdefault(h['code'], h)
    for m in toc:
        if len(m['code']) == 3 and '6' + m['code'] in body_major:
            toc_anom.append({'page': m['tocPage'], 'issue': 'TOC code %s resolved to %s from the detailed list' % (
                m['code'], '6' + m['code']), 'text': m['name']})
            m['code'] = '6' + m['code']

    # ---------------------------------------------------------------- rows
    tocmap = {}
    for m in toc:
        tocmap.setdefault(m['code'], m)
    rows = []
    for m in toc:
        if tocmap[m['code']] is not m:
            continue
        b = body_major.get(m['code'])
        name, toc_name = m['name'], ''
        if b and b['name'] and b['name'] != m['name']:
            ratio = difflib.SequenceMatcher(None, b['name'].lower(), m['name'].lower()).ratio()
            if ratio >= 0.9 and len(b['name']) <= len(m['name']) + 8:
                name, toc_name = b['name'], m['name']  # the detailed list wins over a spelling variant in the contents
        rows.append({'level': 'major', 'major': m['code'], 'subMajor': '', 'code': m['code'], 'name': name,
                     'tocName': toc_name, 'footnotes': m['footnotes'], 'page': b['page'] if b else m['tocPage'],
                     'tocPage': m['tocPage'], 'bodyName': b['name'] if b else '', 'division': m['division'],
                     'part': m['part'], 'sector': m['sector'], 'subSector': m['subSector'], 'group': m['group'],
                     'nature': nature(m['code'], m['part']), 'rule': b['rule'] if b and b['rule'] not in m['name'] else '', 'subMajorRule': ''})
    tmpl_text = {(h['major'], h['code']): h['template'] for h in heads if h['level'] == 'template'}
    for h in heads:
        if h['level'] == 'major':
            continue
        t = tocmap.get(h['major'])
        rows.append({'level': h['level'], 'major': h['major'], 'subMajor': h['subMajor'], 'code': h['code'],
                     'name': h['name'], 'tocName': '', 'footnotes': h['footnotes'], 'page': h['page'], 'tocPage': '',
                     'bodyName': '', 'division': t['division'], 'part': t['part'], 'sector': t['sector'],
                     'subSector': t['subSector'], 'group': t['group'], 'nature': nature(h['major'], t['part']),
                     'rule': h['rule'], 'subMajorRule': tmpl_text.get((h['major'], h['subMajor']), '')})

    # ---------------------------------------------------------------- validation
    report = {'source': {'file': os.path.basename(a.pdf), 'bytes': len(blob), 'sha256': sha, 'pages': len(pages),
                         'correctionSlip': a.slip, 'slipDate': a.slip_date,
                         'extractor': 'pdftotext -simple -enc UTF-8 (%s)' % a.pdftotext},
              'anomalies': toc_anom + body_anom}
    v = {}
    dup_major = sorted({m['code'] for m in toc if sum(1 for x in toc if x['code'] == m['code']) > 1})
    v['duplicateMajorsInToc'] = dup_major
    keys, dups = {}, []
    for r in rows:
        k = (r['level'], r['major'], r['subMajor'] if r['level'] in ('minor', 'template') else '', r['code'])
        if k in keys:
            dups.append({'key': '-'.join(x for x in k[1:] if x) + ' (' + r['level'] + ')', 'pages': [keys[k]['page'], r['page']],
                         'names': [keys[k]['name'], r['name']]})
        else:
            keys[k] = r
    v['duplicateCodes'] = dups
    v['minorsWithoutMajor'] = [r['major'] + '-' + r['code'] for r in rows if r['level'] == 'minor' and r['major'] not in tocmap]
    subs = {(r['major'], r['code']) for r in rows if r['level'] in ('subMajor', 'template')}
    v['minorsUnderUnknownSubMajor'] = sorted({r['major'] + '-' + r['subMajor'] for r in rows if r['level'] == 'minor'
                                              and r['subMajor'] != '00' and (r['major'], r['subMajor']) not in subs})
    v['majorsMissingInDetailedList'] = sorted(c for c in tocmap if c not in body_major)
    v['majorsWithoutMinors'] = sorted(c for c in tocmap if not any(r['level'] == 'minor' and r['major'] == c for r in rows))
    v['bodyMajorsNotInToc'] = sorted(c for c in body_major if c not in tocmap)
    v['emptyNames'] = [r['major'] + '-' + r['subMajor'] + '-' + r['code'] for r in rows if not r['name']]
    v['nameMismatchTocVsBody'] = [{'code': r['code'], 'toc': r['name'], 'body': r['bodyName']} for r in rows
                                  if r['level'] == 'major' and r['bodyName']
                                  and re.sub(r'[^a-z]', '', r['name'].lower()) != re.sub(r'[^a-z]', '', r['bodyName'].lower())]
    report['validation'] = v
    cnt = {}
    for r in rows:
        key = r['level']
        cnt.setdefault(key, 0)
        cnt[key] += 1
    report['counts'] = cnt
    by = {}
    for r in rows:
        k = (r['division'] + ' / ' + r['part']).strip(' /')
        by.setdefault(k, {'major': 0, 'subMajor': 0, 'minor': 0, 'template': 0})
        by[k][r['level']] += 1
    report['countsByPart'] = by
    rng = {}
    for r in rows:
        k = r['major'][0] + 'xxx' if r['major'] not in ('8000',) else '8000'
        rng.setdefault(k, {'major': 0, 'subMajor': 0, 'minor': 0, 'template': 0})
        rng[k][r['level']] += 1
    report['countsByRange'] = dict(sorted(rng.items()))
    sec = {}
    for r in rows:
        if r['level'] == 'major':
            k = r['part'] + ' / ' + r['sector']
            sec[k] = sec.get(k, 0) + 1
    report['majorsBySector'] = sec
    # spot check: 30 random rows, the row's code and first word of its name must occur on its page
    rnd = random.Random(a.seed)
    pool = [r for r in rows if r['level'] != 'template']
    sample = rnd.sample(pool, min(30, len(pool)))
    sc = []
    for r in sample:
        flat = re.sub(r'\s+', ' ', norm(pages[int(r['page']) - 1])).lower()
        words = [w.lower() for w in re.findall(r"[A-Za-z]{3,}", r['name'])][:3]
        ok_code = re.search(r'(?<!\d)' + r['code'] + r'(?!\d)', flat) is not None
        # the name must follow its own code on the page (within 160 characters, words in order)
        pat = re.compile(r'(?<!\d)' + r['code'] + r'(?!\d).{0,160}?' + '.*?'.join(map(re.escape, words)))
        ok_name = bool(pat.search(flat)) if words else True
        sc.append({'row': '%s %s-%s-%s' % (r['level'], r['major'], r['subMajor'], r['code']), 'name': r['name'],
                   'page': r['page'], 'codeOnPage': ok_code, 'nameFollowsCode': ok_name})
    report['spotCheck'] = {'seed': a.seed, 'passed': sum(1 for s in sc if s['codeOnPage'] and s['nameFollowsCode']),
                           'of': len(sc), 'rows': sc}
    report['standardMinors'] = len(std)

    os.makedirs(a.out, exist_ok=True)
    cols = ['level', 'major', 'subMajor', 'code', 'name', 'footnotes', 'page', 'division', 'part', 'sector',
            'subSector', 'group', 'nature', 'rule', 'subMajorRule', 'tocName']
    with open(os.path.join(a.out, 'lmmh-heads.csv'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('# SPDX-License-Identifier: Apache-2.0\n')
        f.write('# LMMH (CGA), correction slips up to %s dated %s; SHA-256 %s; generated by tools/lmmh/extract_lmmh.py\n'
                % (a.slip, a.slip_date, sha))
        w = csv.DictWriter(f, fieldnames=cols, extrasaction='ignore', lineterminator='\n')
        w.writeheader()
        for r in rows:
            w.writerow(r)
    with open(os.path.join(a.out, 'lmmh-standard-minors.csv'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('# SPDX-License-Identifier: Apache-2.0\n')
        f.write('# LMMH (CGA) General Directions, illustrative list of standard minor heads; CS %s\n' % a.slip)
        w = csv.DictWriter(f, fieldnames=['code', 'name', 'directions', 'page'], lineterminator='\n')
        w.writeheader()
        for r in std:
            w.writerow(r)
    with open(os.path.join(a.out, 'lmmh-report.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(report, f, indent=1, ensure_ascii=False)
        f.write('\n')
    print(json.dumps({'counts': cnt, 'standardMinors': len(std), 'anomalies': len(report['anomalies']),
                      'spotCheck': '%d/%d' % (report['spotCheck']['passed'], report['spotCheck']['of']),
                      **{k: (len(x) if isinstance(x, list) else x) for k, x in v.items()}}, indent=1))


if __name__ == '__main__':
    main()
