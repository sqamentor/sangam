import hashlib, sys
root_notice, op_notice = sys.argv[1], sys.argv[2]
OP_SHA = "E3C6927C722EE2A629577409B3F7202B16A435DB5D01D6C9F3FD70269295B628"
op = open(op_notice, "rb").read()
assert hashlib.sha256(op).hexdigest().upper() == OP_SHA
op = op.replace(b"\r\n", b"\n")
assert b"\r" not in op
op = op.decode("utf-8")
raw_old = open(root_notice, "rb").read()
eol = b"\r\n" if b"\r\n" in raw_old else b"\n"
old = raw_old.replace(b"\r\n", b"\n").decode("utf-8")
assert "\r" not in old
head = ("Sangam\nCopyright 2026 The Sangam authors\n\nThis product is licensed under the Apache License, Version 2.0 (see LICENSE).\n\n")
assert old.startswith(head)
intro_old = ("Sangam builds on, and its evaluation spike depends on, the following open-source projects,\n"
             "both licensed under the Apache License, Version 2.0:\n\n"
             "- Operaton (https://github.com/operaton/operaton): Copyright the Operaton contributors;\n"
             "  derived from Camunda 7 (Copyright Camunda Services GmbH and contributors).\n"
             "- Flowable (https://github.com/flowable/flowable-engine): Copyright Flowable AG and contributors.\n\n")
assert old[len(head):].startswith(intro_old)
rest = old[len(head) + len(intro_old):]
feel_at = rest.index("DMN FEEL engine (SG-27")
ports_and_marks = rest[:feel_at]
feel_and_bpmnio = rest[feel_at:]
bpmnio_at = feel_and_bpmnio.index("The bpmn-js, dmn-js and form-js libraries")
feel = feel_and_bpmnio[:bpmnio_at]
bpmnio = feel_and_bpmnio[bpmnio_at:]

sep = "-" * 100 + "\n"
new = (head +
       "This NOTICE aggregates the notices Sangam carries for the work it builds on (M-088). The notices of the\n"
       "third-party dependencies shipped in each distribution (their own NOTICE files, the licence of each component and\n"
       "the licence texts) are generated for every release by sangam/deploy/licence/notice_bundle.py into\n"
       "sangam-notices-<version>.tgz, and THIRD-PARTY-LICENSES-<version>.txt (sangam/deploy/licence/licence_book.py) is\n"
       "the book of all of them. This file records licence notices; it is not legal advice.\n\n"
       "Sangam builds on, and its evaluation spike depends on, the following open-source projects,\n"
       "both licensed under the Apache License, Version 2.0:\n\n"
       "- Operaton (https://github.com/operaton/operaton): Copyright the Operaton contributors;\n"
       "  derived from Camunda 7 (Copyright Camunda Services GmbH and contributors).\n"
       "- Flowable (https://github.com/flowable/flowable-engine): Copyright Flowable AG and contributors.\n\n"
       + ports_and_marks +
       sep +
       "1. Operaton NOTICE, reproduced verbatim (M-088). Source: the NOTICE file of\n"
       "   https://github.com/operaton/operaton at commit ccea9712f28a73b380829621fa6c2fb6540685f1 (SHA-256\n"
       "   " + OP_SHA + ", stored with CRLF line endings; reproduced word for word with LF line endings). The\n"
       "   Operaton 2.2.0 jars Sangam ships carry no NOTICE"
       " file of their own. The License Book it names is Operaton's; Sangam's is THIRD-PARTY-LICENSES-<version>.txt.\n"
       + sep + "\n" + op.rstrip("\n") + "\n\n" + sep +
       "2. Flowable logic ports (sangam/sangam-flowable-ports/NOTICE): the module re-implements on Operaton the logic\n"
       "   of several Flowable classes (Apache License 2.0); no Flowable source file is copied, and the Flowable\n"
       "   checkout ships a LICENSE but no NOTICE file.\n"
       + sep + "\n" +
       "3. " + feel.rstrip("\n") + "\n\n" +
       "4. " + bpmnio)
assert new.count(op.rstrip("\n")) == 1
open(root_notice, "wb").write(new.encode("utf-8").replace(b"\n", eol))
print("ok", len(new))
