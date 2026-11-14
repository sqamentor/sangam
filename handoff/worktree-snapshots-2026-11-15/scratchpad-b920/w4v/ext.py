import sys; sys.path.append("C:/Users/Lokendra Singh/AppData/Roaming/Python/Python313/site-packages")
import sys, os, fitz as pymupdf
d = r'D:\GITHUB  Project\sangam-handoff\evidence\statutory'
out = sys.argv[1]
os.makedirs(out, exist_ok=True)
for f in sorted(os.listdir(d)):
    if not f.endswith('.pdf'): continue
    doc = pymupdf.open(os.path.join(d, f))
    t = '\n'.join(f'=====PAGE {i+1}=====\n' + p.get_text() for i, p in enumerate(doc))
    open(os.path.join(out, f + '.txt'), 'w', encoding='utf-8').write(t)
    import re
    m = re.findall(r'G\.S\.R\.\s*\d+\s*\(E\)|S\.O\.\s*\d+\s*\(E\)', t)
    print(f, doc.page_count, sorted(set(m))[:10])
