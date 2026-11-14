import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
def rep(old, new):
    global s
    assert s.count(old) == 1, (old[:90], s.count(old))
    s = s.replace(old, new)

rep(""" * - plaintext entries from the old localStorage queue are encrypted on first load (unclaimed, bound to the tenant and
 *   this installation), replayed only after the current user confirms they are theirs, and the plaintext is deleted
 *   once they are bound to that user.
""", """ * - plaintext entries from the old localStorage queue (owner decisions 2026-11-12, second round) are encrypted on the
 *   first load, bound to the signed-in user who loads them (tenant, installation, user in the AAD); the plaintext is
 *   deleted as soon as the encrypted copies are durably stored (a failed store keeps the plaintext and the import is
 *   retried on the next open or sync); the records are sent only after that user confirms them; no other user, of
 *   the same tenant or another, can open, send or discard them.
""")
rep("type EntryState = 'PENDING' | 'KEPT';", "/** UNCONFIRMED: imported from the old plaintext queue, waiting for its user to confirm before it is sent. */\ntype EntryState = 'PENDING' | 'KEPT' | 'UNCONFIRMED';")
rep("""  /** ownerTag, or UNCLAIMED for an imported plaintext entry nobody has confirmed. */
  owner: string;
  tenantTag: string;
""", """  owner: string;
""")
rep("const UNCLAIMED = 'unclaimed';\n", "")
rep("""    readonly ownerTag: string,
    private readonly tenantTag: string,
  ) {}""", """    readonly ownerTag: string,
  ) {}

  /** hex SHA-256 of tenantId|userId: the owner of an entry, stored beside it so lists need no decryption. */
  static async ownerTagOf(c: Crypto, tenantId: string, userId: string): Promise<string> {
    return hex(await c.subtle.digest('SHA-256', javaUtf8(`${tenantId}|${userId}`)));
  }

  /**
   * How many of this user's field records are on the device and not yet accepted by the server (queued, kept after a
   * refusal, or imported and unconfirmed). Reads only owner tags: no key is created and nothing is decrypted.
   */
  static async unsyncedCount(backend: QueueBackend, c: Crypto, tenantId: string, userId: string): Promise<number> {
    const tag = await FieldSync.ownerTagOf(c, tenantId, userId);
    const own = (e: { value: { owner?: string } }) => e.value?.owner === tag;
    return (await backend.list<PacketEntry>('entries')).filter(own).length + (await backend.list<RecordEntry>('legacy')).filter(own).length;
  }""")
rep("""    const tag = async (s: string) => hex(await o.crypto.subtle.digest('SHA-256', javaUtf8(s)));
    const f = new FieldSync(o, install, await tag(`${o.tenantId}|${o.userId}`), await tag(o.tenantId));
    await f.importPlaintext();
    return f;""", """    const f = new FieldSync(o, install, await FieldSync.ownerTagOf(o.crypto, o.tenantId, o.userId));
    await f.importPlaintext();
    return f;""")
rep("""  private recordBinding(owner: string): Binding {
    return { tenantId: this.o.tenantId, deviceId: this.install.installId, userId: owner === UNCLAIMED ? '' : this.o.userId };
  }""", """  /** Visit records (and imported old records) are bound to tenant, installation and the signed-in user. */
  private recordBinding(): Binding {
    return { tenantId: this.o.tenantId, deviceId: this.install.installId, userId: this.o.userId };
  }""")
rep("""this.recordBinding(this.ownerTag), idempotencyKey, JSON.stringify(plain), this.now());
    const entry: RecordEntry = { owner: this.ownerTag, tenantTag: this.tenantTag, env, state: 'PENDING' };""", """this.recordBinding(), idempotencyKey, JSON.stringify(plain), this.now());
    const entry: RecordEntry = { owner: this.ownerTag, env, state: 'PENDING' };""")
rep("""    const pt = await openEnvelope(this.subtle, this.install.queueKey, this.recordBinding(e.owner), e.env);""", """    if (e.owner !== this.ownerTag) return null;
    const pt = await openEnvelope(this.subtle, this.install.queueKey, this.recordBinding(), e.env);""")
rep("""    for (const { key, value } of await this.ownRecords()) {
      const r = await this.openRecord(value);
      if (r) out.push({ kind: 'record',""", """    for (const { key, value } of await this.ownRecords()) {
      if (value.state === 'UNCONFIRMED') continue; // shown through unclaimedCount until the user confirms them
      const r = await this.openRecord(value);
      if (r) out.push({ kind: 'record',""")
# sync retries a failed import
rep("""    try {
      await this.syncRecords(report);""", """    try {
      await this.importPlaintext(); // retries an import whose encrypted store failed (no-op when nothing is left)
      await this.syncRecords(report);""")
start = s.index("  /** First load: every old plaintext record not yet held encrypted is sealed as unclaimed (tenant + installation). */")
end = s.index("/** Contract item 5:")
s = s[:start] + """  /**
   * First load: every old plaintext record not yet held encrypted is sealed for the signed-in user (UNCONFIRMED), and
   * the plaintext is removed once the encrypted copies are durably stored (the backend's write resolves after its
   * transaction completes). If the store fails, the plaintext stays and the import runs again on the next open or sync.
   */
  private async importPlaintext(): Promise<void> {
    const plain = this.readPlaintext();
    if (plain.length === 0) return;
    try {
      const held = new Set((await this.o.backend.list<RecordEntry>('legacy')).map((e) => e.value.env.op));
      const ops: WriteOp[] = [];
      for (const r of plain) {
        if (held.has(r.key)) continue;
        held.add(r.key);
        const env = await seal(this.o.crypto, this.install.queueKey, 'local', this.recordBinding(), r.key, JSON.stringify(r), this.now());
        const entry: RecordEntry = { owner: this.ownerTag, env, state: 'UNCONFIRMED' };
        ops.push({ put: 'legacy', key: `${this.ownerTag}:${r.key}`, value: entry });
      }
      await this.o.backend.write(ops);
    } catch {
      return; // not stored: keep the plaintext, retry later
    }
    try {
      this.o.legacyStorage?.removeItem(LEGACY_QUEUE_KEY);
    } catch {
      /* storage unavailable: nothing more can be done here */
    }
  }

  private async unconfirmed(): Promise<{ key: string; value: RecordEntry }[]> {
    return (await this.ownRecords()).filter((e) => e.value.state === 'UNCONFIRMED');
  }

  /** How many of this user's records from the old queue wait for confirmation. */
  async unclaimedCount(): Promise<number> {
    return (await this.unconfirmed()).length;
  }

  /** This user confirmed the old records are theirs: the next sync sends them (same envelope, same key). */
  async claimUnclaimed(): Promise<number> {
    const list = await this.unconfirmed();
    await this.o.backend.write(list.map(({ key, value }) => ({ put: 'legacy', key, value: { ...value, state: 'PENDING' } satisfies RecordEntry })));
    return list.length;
  }
}

""" + s[end:]
open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
