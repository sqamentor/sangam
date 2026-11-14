import hashlib, json, re, sys, zipfile
jar, texts = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(jar)
picks = [("Christian Niles", "javanet/staxutils/BaseXMLEventReader.java"),
         ("Sun Microsystems", "javanet/staxutils/ContentHandlerToXMLEventWriter.java"),
         ("John Kristian", None)]
blocks = []
for who, name in picks:
    if name is None:
        name = next(n for n in sorted(z.namelist()) if n.endswith(".java")
                    and "Copyright (c) 2006, John Kristian" in z.read(n).decode("latin-1").split("package ", 1)[0])
    head = z.read(name).decode("latin-1").split("package ", 1)[0]
    body = head[head.index("/*") + 2:head.rindex("*/")]
    lines = [re.sub(r"^\s*\* ?", "", l).rstrip() for l in body.split("\n")]
    lines = [l for l in lines if not l.startswith("$Id")]
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    lines = [l.replace("\t", "    ") for l in lines]
    blocks.append((name, "\n".join(lines)))
intro = ("stax-utils 20070216 (net.java.dev.stax-utils:stax-utils). The project ships no separate licence file; each\n"
         "source file carries one of the three BSD-style licence headers below, verbatim from the project's sources jar\n"
         "on Maven Central (comment markers and CVS $Id lines removed). 65 of its 72 source files carry a header;\n"
         "7 carry none.\n")
text = intro + "".join(f"\n---- as in {n} ----\n\n{b}\n" for n, b in blocks)
data = text.encode("utf-8")
with open(f"{texts}/LicenseRef-BSD-stax-utils.txt", "wb") as f:
    f.write(data)
src_path = f"{texts}/SOURCES.json"
src = json.load(open(src_path, encoding="utf-8"))
src["texts"]["LicenseRef-BSD-stax-utils"] = {
    "source": "net.java.dev.stax-utils:stax-utils:20070216 sources jar, Maven Central, fetched under decision 233(2) "
              "(DOWNLOADS-WAVE4.md 2026-11-13): licence headers of " + ", ".join(n for n, _ in blocks),
    "artefact_sha256": hashlib.sha256(open(jar, "rb").read()).hexdigest().upper(),
    "entry": "; ".join(n for n, _ in blocks),
    "change": "three header blocks joined under a short factual introduction; comment markers, tabs and CVS $Id lines removed, wording unchanged",
    "sha256": hashlib.sha256(data).hexdigest().upper()}
src["texts"] = dict(sorted(src["texts"].items()))
with open(src_path, "w", encoding="utf-8", newline="\n") as f:
    json.dump(src, f, indent=2)
    f.write("\n")
print(src["texts"]["LicenseRef-BSD-stax-utils"]["sha256"], len(data))
