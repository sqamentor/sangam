import sys
p = sys.argv[1]
d = open(p, "rb").read().decode("utf-8")
crlf = "\r\n" in d
d = d.replace("\r\n", "\n")
oldq = ('''   "Evaluates the model's named query read-only over the instance state; nothing changes. 404 for an unknown handler; "
   "422 VALIDATION_FAILED for missing or "
   "mistyped arguments; evaluation is bounded in time and size (R19).",''')
newq = ('''   "Evaluates the model's named query read-only over the instance state; nothing changes. 404 for an unknown instance "
   "or handler name; 422 VALIDATION_FAILED for missing or mistyped arguments, HANDLER_EVALUATION_FAILED when the "
   "strict-mode FEEL evaluation fails, RESULT_TOO_LARGE when the result exceeds 10000 values or 32 levels; evaluation "
   "is bounded in time and size (R19).",''')
oldu = ('''   "caseVersion; obligations the change affects are re-evaluated. 404 unknown handler; 409 APPROVAL_INVALIDATED, "
   "REVISION_CONFLICT; 422 VALIDATION_FAILED, SOD_VIOLATION.",''')
newu = ('''   "caseVersion; obligations the change affects are re-evaluated. 400 REASON_REQUIRED without a reason; 404 unknown "
   "instance or handler name; 409 APPROVAL_INVALIDATED, REVISION_CONFLICT, STATE_INVALID when the handler declares "
   "activities and the instance is at none of them (E21); 422 VALIDATION_FAILED (arguments), HANDLER_EVALUATION_FAILED "
   "(strict FEEL failure), RESULT_TOO_LARGE (over 10000 values or 32 levels), SOD_VIOLATION.",''')
assert d.count(oldq) == 1 and d.count(oldu) == 1
d = d.replace(oldq, newq).replace(oldu, newu)
if crlf: d = d.replace("\n", "\r\n")
open(p, "wb").write(d.encode("utf-8"))
print("ok", crlf)
