import sys
from pypdf import PdfReader
r=PdfReader(sys.argv[1]); print("pages",len(r.pages))
out=open(sys.argv[2],"w",encoding="utf-8")
for i,p in enumerate(r.pages):
    out.write(f"\n=====PAGE {i+1}=====\n"); out.write(p.extract_text() or "")
