import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()


def rep(old, new):
    global s
    assert s.count(old) == 1, old
    s = s.replace(old, new)


rep('''**Evidence level:** compiled, with `MainTest` run against a fake HTTP layer. Never run against a real server or identity provider. Device-flow polling has no test.''',
    '''**Evidence level:** compiled. `MainTest`, `TokenStoreTest` and `KeyProtectorTest` (M-093) are written against a fake HTTP layer, a fake keychain and a fake process runner; the M-093 tests have not been run yet (test phase). Never run against a real server, identity provider or OS keychain. Device-flow polling has no test.''')
rep('''| `login` | OIDC discovery, then device flow or client credentials | https required except on loopback. The secret comes from an environment variable only |''',
    '''| `login` | OIDC discovery, then device flow or client credentials | https required except on loopback. The secret comes from an environment variable only; only the variable's name is saved |
| `logout` | none | Deletes the saved tokens and the keychain key; keeps the server setting |''')
rep('''- **Gap:** the access token is stored in `~/.sangam/cli.json` without refresh; on non-POSIX file systems it relies on the user profile's permissions. There is no OS keychain support.''',
    '''- **Tokens (M-093):**
  - `~/.sangam/cli.json` holds settings only: server, issuer, token endpoint, client id, account id, and the name of the secret variable.
  - The tokens are kept in `~/.sangam/credentials/<account>.json`, which is always owner-only: mode 0600, set at creation, on POSIX; a single owner access entry on Windows NTFS. The file is written to a temporary file and then moved into place.
  - `SANGAM_TOKEN_STORE` chooses where the tokens go:
    - `auto` (the default): the tokens are encrypted with AES-256-GCM under a random 256-bit key, with the account as associated data. The key is held by the OS secret store, through the tool the platform already ships: macOS `/usr/bin/security` (login keychain), Linux `secret-tool` (Secret Service), or Windows `powershell.exe` with the Data Protection API for the current user. The key is passed on stdin, never on a command line, and every store is read back to verify it. If no store works, the tokens go to the owner-only file in clear text, with a note on stderr.
    - `keychain`: the keychain is required; login fails without one.
    - `file`: always the owner-only file, for CI runners and servers.
  - The access token is renewed 60 s before `expires_in` runs out, and once after a 401; the same request (same `Idempotency-Key`) is then sent again.
    - With a refresh token, the CLI uses the `refresh_token` grant. A rotated refresh token replaces the old one.
    - A service principal repeats `client_credentials` with the secret read from the same environment variable.
    - If renewal fails, the CLI exits 3 and prints the OAuth error code only.
  - `SANGAM_TOKEN` overrides everything; it is never stored or renewed.
  - A `cli.json` from before M-093 that still holds `accessToken` is migrated once into the store.
  - No command prints a token.
- **Limits:** none of the three platform tools has been run in a test (fakes only). On Linux without `secret-tool` or a running Secret Service (headless hosts), `auto` uses the file. The Windows access list replaces the file's DACL but is not marked protected, so a later change to the parent's inheritable entries can apply to it again.''')
rep('''Jackson (Apache-2.0), shaded into the jar. `maven-shade-plugin` at build time only.''',
    '''Jackson (Apache-2.0), shaded into the jar. `maven-shade-plugin` at build time only. The keychain needs no new dependency: it uses JDK `javax.crypto` and the platform's own tools.''')
open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
