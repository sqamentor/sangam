"""Append the UI-PARITY-2 notes to a TESTING-GAP-REGISTER.md once (idempotent), keeping its EOL style."""
import sys

NOTES = [
    "UI-PARITY-2 (2026-11-08/09, `-Dsangam.target=target-uip2`): `taskForm.test.ts` timeout FIXED. Root cause:"
    " `await import('../../api/endpoints')` inside the test body, so vitest transformed and loaded the API module graph"
    " inside the 5 s test timeout (1.9 s idle, more under full-suite load). Now a static import (collection time); the"
    " slow test takes 60-113 ms in three consecutive full runs on 2026-11-09 (89/89 each, run while a Maven build loaded"
    " the machine); timeout unchanged.",

    "Related finding, **FIXED (UI-PARITY-2, single-verified; register id to be assigned):** on H2, the development"
    " database, every VI.4 predicate of a signed-in (non-system) identity failed to prepare. `SecurityDialect.H2.bitAnd`"
    " emitted `BITAND(x.perms, ?)`; H2 cannot type a bind parameter that is a function argument (\"Unknown data type:"
    " ?n\", error 50004, in PostgreSQL and regular mode alike) and has no `&` operator. So on H2 the READ_HISTORY"
    " read-model lists answered 500 to every signed-in user, and so did every other `PolicyCompiler.visible`/`allow`"
    " caller (registry, case, programme, resources, ops search, AI retrieval). Two module tests had hidden it by"
    " rewriting the compiled SQL (`OpsTest`, `AiRuntimeTest`). Fix: on H2 `bitAnd` casts both operands to BIGINT"
    " (`AUTH_EFFECTIVE.perms` is BIGINT); PostgreSQL, SQL Server and MySQL (`&`) and Oracle (`BITAND`) keep their proven"
    " SQL. Tests: `SecurityDialectTest` (each dialect's SQL; on H2 in both modes a bound bit, a bit above 32 bits, the"
    " parameter on either side), `PolicyCompilerH2Test` (list and object check of a non-system identity: scope grant,"
    " exact grant, deny wins, other bit, other tenant), the SQL rewrites removed from `OpsTest` and `AiRuntimeTest`, and"
    " `SangamUiParityApiTest` now reads the history lists over HTTP as the signed-in dev user and binds non-system"
    " identities on the port (the system-identity workaround is gone). Red run without the fix: `SecurityDialectTest`"
    " 3 of 3, `PolicyCompilerH2Test` 2 of 2, `OpsTest` 1, `AiRuntimeTest` 2, `SangamUiParityApiTest` 2 (500 on the"
    " history lists). The core `Dialect.bitAnd` (spike `AuthorizationPolicy`) has no H2 caller and is unchanged.",
]

if __name__ == '__main__':
    f = sys.argv[1]
    raw = open(f, encoding='utf-8', newline='').read()
    crlf = '\r\n' in raw
    c = raw.replace('\r\n', '\n')
    added = 0
    for n in NOTES:
        if n[:60] in c:
            continue
        c = c.rstrip('\n') + '\n\n' + n + '\n'
        added += 1
    out = c.replace('\n', '\r\n') if crlf else c
    open(f, 'w', encoding='utf-8', newline='').write(out)
    print('appended', added, 'note(s) to', f)
