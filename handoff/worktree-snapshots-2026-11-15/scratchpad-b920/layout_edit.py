import sys
p = sys.argv[1]
s = open(p, 'rb').read().decode('utf-8').replace('\r\n', '\n')
def rep(old, new):
    global s
    assert s.count(old) == 1, (old[:90], s.count(old))
    s = s.replace(old, new)
rep("import { registerAnnouncer } from '../lib/hooks';\n", "import { announce, registerAnnouncer } from '../lib/hooks';\nimport { syncFieldRecords, unsentFieldRecords } from '../lib/offline/session';\nimport { LogoutPrompt } from './LogoutPrompt';\n")
rep("""  const first = useRef(true);
""", """  const first = useRef(true);
  // M-001: signing out with unsent field records asks first (non-blocking); the records stay encrypted and bound
  const [unsent, setUnsent] = useState<number | null>(null);
  const [syncing, setSyncing] = useState(false);
  const signOut = async () => {
    const n = me ? await unsentFieldRecords(me) : 0;
    if (n > 0) setUnsent(n);
    else void logout();
  };
  const syncBeforeLogout = async () => {
    if (!me) return;
    setSyncing(true);
    try {
      const left = await syncFieldRecords(me);
      setUnsent(left > 0 ? left : null);
      if (left === 0) announce(t('field.synced'));
    } catch {
      /* offline or refused: the prompt stays with the same count */
    } finally {
      setSyncing(false);
    }
  };
""")
rep("""              <button type="button" onClick={() => void logout()}>
                {t('auth.signOut')}
              </button>
            </div>
          )}
        </div>
      </header>
""", """              <button type="button" onClick={() => void signOut()}>
                {t('auth.signOut')}
              </button>
            </div>
          )}
        </div>
      </header>
      {unsent !== null && <LogoutPrompt count={unsent} busy={syncing} onSync={() => void syncBeforeLogout()} onLogout={() => void logout()} />}
""")
open(p, 'wb').write(s.replace('\n', '\r\n').encode('utf-8'))
print('ok')
