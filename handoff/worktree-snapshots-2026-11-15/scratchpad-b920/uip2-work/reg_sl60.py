"""Register: insert the SL-60 row after the SL-59 row and point the UI-PARITY-2 end note at it (exact, once)."""
import sys

sys.path.insert(0, r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\uip2-work")
from reg_notes import NOTES  # noqa: E402  (NOTES[1] is the note text to replace)

ROW = (
    "| SL-60 | MEDIUM correctness (H2, development database) | (found by UI-PARITY-2) On H2 every VI.4 predicate of a"
    " signed-in, non-system identity failed to prepare: `SecurityDialect.H2.bitAnd` emitted `BITAND(x.perms, ?)`, and H2"
    " cannot type a bind parameter that is a function argument (\"Unknown data type: ?n\", error 50004, in PostgreSQL"
    " and regular mode alike) and has no `&` operator. On H2 the READ_HISTORY read-model lists therefore answered 500"
    " to every signed-in user, and so did every other `PolicyCompiler.visible`/`allow` caller (registry, case,"
    " programme, resources, ops search, AI retrieval). `OpsTest` and `AiRuntimeTest` had hidden it by rewriting the"
    " compiled SQL. | Root fix in the dialect layer: on H2 `bitAnd` casts both operands to BIGINT (`AUTH_EFFECTIVE.perms`"
    " is BIGINT); PostgreSQL, SQL Server, MySQL (`&`) and Oracle (`BITAND`) keep their SQL unchanged. Tests:"
    " `SecurityDialectTest` (each dialect's SQL; on H2 in both modes a bound bit, a bit above 32 bits, the parameter on"
    " either side), `PolicyCompilerH2Test` (list and object check of a non-system identity: scope grant, exact grant,"
    " deny wins, other bit, other tenant), the SQL rewrites removed from `OpsTest` and `AiRuntimeTest`, and"
    " `SangamUiParityApiTest` reads the history lists over HTTP as the signed-in dev user and binds non-system"
    " identities on the port (its system-identity workaround is gone). Red without the fix: `SecurityDialectTest` 3 of"
    " 3, `PolicyCompilerH2Test` 2 of 2, `OpsTest` 1, `AiRuntimeTest` 2, `SangamUiParityApiTest` 2 (500 on the history"
    " lists). The core `Dialect.bitAnd` (spike `AuthorizationPolicy`) has no H2 caller and is unchanged. |"
    " FIXED in working tree (single-verified, OPEN until the SD-428 check) |"
)
POINTER = ("Related finding, **FIXED (SL-60, single-verified):** the H2 VI.4 predicate defect found by UI-PARITY-2 (500 on"
           " the READ_HISTORY lists for every signed-in user on H2); root cause, fix and tests are in the SL-60 row.")

if __name__ == '__main__':
    f = sys.argv[1]
    raw = open(f, encoding='utf-8', newline='').read()
    crlf = '\r\n' in raw
    lines = raw.replace('\r\n', '\n').split('\n')
    if any(l.startswith('| SL-60 |') for l in lines):
        sys.exit('an SL-60 row already exists')
    at = [i for i, l in enumerate(lines) if l.startswith('| SL-59 |')]
    if len(at) != 1:
        sys.exit('SL-59 row not found exactly once')
    lines.insert(at[0] + 1, ROW)
    c = '\n'.join(lines)
    if c.count(NOTES[1]) != 1:
        sys.exit('UI-PARITY-2 note not found exactly once')
    c = c.replace(NOTES[1], POINTER)
    open(f, 'w', encoding='utf-8', newline='').write(c.replace('\n', '\r\n') if crlf else c)
    print('SL-60 row inserted after line', at[0] + 1, '; note now points at SL-60')
