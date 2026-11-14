/** The field client's calls (FieldWorkController: device registration, upload, pull; WorkspaceController: record). */
const fieldTransport: FieldTransport = {
  registerDevice: (b) => api.field.registerDevice(b),
  upload: (deviceId, packets) => api.field.syncUpload(deviceId, packets),
  pull: (deviceId, cursor) => api.field.syncPull(deviceId, cursor),
  recordVisit: (visitId, body, key) => api.field.record(visitId, body, key),
};

function localStore(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

/** Open assignment states, as the server's own visit list uses them (FieldRestService.java:43-44). */
const OPEN_ASSIGNMENT = new Set(['ASSIGNED', 'IN_PROGRESS', 'OVERDUE', 'REJECTED']);

const label = (l: Record<string, string> | undefined, lang: string, fallback: string) => (l ? l[lang] ?? Object.values(l)[0] ?? fallback : fallback);

/** Best-effort position for the packet's fix (geofence checks); none when denied, unavailable or slow. */
function locate(): Promise<GpsFix | undefined> {
  return new Promise((resolve) => {
    if (typeof navigator === 'undefined' || !navigator.geolocation) return resolve(undefined);
    navigator.geolocation.getCurrentPosition(
      (p) =>
        resolve({
          lat: p.coords.latitude,
          lon: p.coords.longitude,
          accuracyM: p.coords.accuracy,
          altitudeM: p.coords.altitude ?? undefined,
          provider: 'browser',
          mockLocation: false,
          fixTime: javaInstant(new Date(p.timestamp)),
        }),
      () => resolve(undefined),
      { enableHighAccuracy: true, timeout: 10_000, maximumAge: 60_000 },
    );
  });
}

type AnswerValue = boolean | string | number | string[];

/** One answer input per template item type (FieldTemplate.ItemType); media and position items are not captured here. */
function ItemInput({ item, value, onChange, lang }: { item: TemplateItem; value: AnswerValue | undefined; onChange: (v: AnswerValue | undefined) => void; lang: string }) {
  const { t } = useTranslation();
  const l = label(item.label, lang, item.id);
  switch (item.type) {
    case 'BOOLEAN':
      return <Checkbox label={l} checked={value === true} onChange={(v) => onChange(v)} />;
    case 'CHOICE':
      return (
        <SelectField
          label={l}
          required={item.required}
          value={typeof value === 'string' ? value : ''}
          onChange={(v) => onChange(v || undefined)}
          placeholder={t('form.choose')}
          options={(item.options ?? []).map((o) => ({ value: o.value, label: label(o.label, lang, o.value) }))}
        />
      );
    case 'MULTI_CHOICE': {
      const set = Array.isArray(value) ? value : [];
      return (
        <fieldset className="field">
          <legend>{l}</legend>
          {(item.options ?? []).map((o) => (
            <Checkbox
              key={o.value}
              label={label(o.label, lang, o.value)}
              checked={set.includes(o.value)}
              onChange={(v) => onChange(v ? [...set, o.value] : set.filter((x) => x !== o.value))}
            />
          ))}
        </fieldset>
      );
    }
    case 'NUMBER':
      return <TextField label={l} type="number" required={item.required} value={value === undefined ? '' : String(value)} onChange={(v) => onChange(v === '' ? undefined : Number(v))} />;
    case 'DATE':
      return <TextField label={l} type="date" required={item.required} value={typeof value === 'string' ? value : ''} onChange={(v) => onChange(v || undefined)} />;
    case 'TEXT':
      return <TextArea label={l} required={item.required} value={typeof value === 'string' ? value : ''} onChange={(v) => onChange(v || undefined)} />;
    default:
      return <Notice tone="warn">{t('field.itemNotHere', { label: l })}</Notice>;
  }
}

/**
 * S15 field work (EC-16, M-001): assignments pulled to this device and task-based visits are captured into the
 * encrypted local queue (LocalQueueContract) and synced when online, each with an id fixed at capture (R41).
 */
export function FieldVisits() {
  const { t, i18n } = useTranslation();
  const { me } = useAuth();
  const visits = useAsync(() => api.field.visits(), []);
  const [client, setClient] = useState<FieldSync | null>(null);
  const [durable, setDurable] = useState(true);
  const [pending, setPending] = useState<PendingItem[]>([]);
  const [unclaimed, setUnclaimed] = useState(0);
  const [cache, setCache] = useState<CachedState | null>(null);
  const [online, setOnline] = useState(navigator.onLine);
  const [open, setOpen] = useState<{ kind: 'visit' | 'assignment'; id: string } | null>(null);
  const [answers, setAnswers] = useState<Record<string, AnswerValue | undefined>>({});
  const [notes, setNotes] = useState('');
  const [template, setTemplate] = useState<PulledTemplate | null>(null);
  const [confirmDiscard, setConfirmDiscard] = useState<string | null>(null);
  const [syncErr, setSyncErr] = useState<Error>();
  const [notice, setNotice] = useState<string | null>(null);
  const tenantId = me?.tenantId;
  const userId = me?.userId;

  const refresh = async (c: FieldSync) => {
    setPending(await c.pending());
    setUnclaimed(await c.unclaimedCount());
    setCache(await c.cached());
  };

  useEffect(() => {
    if (!tenantId || !userId) return;
    let live = true;
    const backend = defaultBackend();
    setDurable(backend.durable);
    FieldSync.open({ backend, crypto: window.crypto, transport: fieldTransport, tenantId, userId, appVersion: pkg.version, platform: 'web', legacyStorage: localStore() })
      .then(async (c) => {
        if (!live) return;
        setClient(c);
        await refresh(c);
      })
      .catch((e: Error) => {
        if (live) setSyncErr(e);
      });
    return () => {
      live = false;
    };
  }, [tenantId, userId]);

  useEffect(() => {
    const on = () => setOnline(true);
    const off = () => setOnline(false);
    window.addEventListener('online', on);
    window.addEventListener('offline', off);
    return () => {
      window.removeEventListener('online', on);
      window.removeEventListener('offline', off);
    };
  }, []);

  const sync = async (c: FieldSync | null = client) => {
    if (!c) return;
    setSyncErr(undefined);
    try {
      await c.pull();
    } catch (e) {
      setSyncErr(e as Error);
    }
    const r = await c.sync();
    if (r.wiped) setNotice(t('field.wiped'));
    else if (r.revoked) setNotice(t('field.revoked'));
    if (r.error) setSyncErr(r.error as Error);
    if (r.wiped) {
      setPending([]);
      setUnclaimed(0);
      setCache(null);
      setClient(null);
      return;
    }
    await refresh(c);
    if (!r.error && r.pending === 0 && r.kept === 0) announce(t('field.synced'));
  };

  useEffect(() => {
    if (online && client) void sync(client);
  }, [online, client]); // eslint-disable-line react-hooks/exhaustive-deps

  const assignments = Object.values(cache?.assignments ?? {}).filter((a) => OPEN_ASSIGNMENT.has(String(a.status)));
  const assignment = open?.kind === 'assignment' ? cache?.assignments[open.id] : undefined;
  const visit = open?.kind === 'visit' ? visits.data?.find((v) => v.id === open.id) : undefined;

  // the assignment's checklist template: pulled with the assignment, else fetched while online
  const templateKey = assignment ? `${String(assignment.templateKey)}:${String(assignment.templateVersion)}` : null;
  useEffect(() => {
    setTemplate(null);
    if (!assignment || !templateKey) return;
    const held = cache?.templates[templateKey];
    if (held) setTemplate(held);
    else if (online) void api.field.template(String(assignment.templateKey), Number(assignment.templateVersion)).then(setTemplate, (e: Error) => setSyncErr(e));
  }, [templateKey, online]); // eslint-disable-line react-hooks/exhaustive-deps

  const start = (kind: 'visit' | 'assignment', id: string) => {
    setOpen({ kind, id });
    setAnswers({});
    setNotes('');
  };

  const saveAssignment = async () => {
    if (!client || !assignment) return;
    try {
      const filled = Object.fromEntries(Object.entries(answers).filter(([, v]) => v !== undefined));
      await client.captureVisit({ assignmentId: String(assignment.id), baseVersion: Number(assignment.version), answers: filled, notes, fix: await locate() });
      setOpen(null);
      announce(t('field.saved'));
      await refresh(client);
      if (online) void sync();
    } catch (e) {
      setSyncErr(e instanceof FieldQueueError ? new Error(t(`field.error.${e.code}`)) : (e as Error));
    }
  };

  const saveVisit = async () => {
    if (!client || !visit) return;
    const body = { answers: Object.fromEntries(Object.entries(answers).map(([k, v]) => [k, typeof v === 'boolean' ? v : String(v ?? '')])), notes, recordedAt: new Date().toISOString() };
    await client.captureRecord(visit.id, body, newIdempotencyKey());
    setOpen(null);
    announce(t('field.saved'));
    await refresh(client);
    if (online) void sync();
  };

  const discard = async (id: string) => {
    if (!client) return;
    await client.discard(id);
    setConfirmDiscard(null);
    await refresh(client);
  };

  const claim = async () => {
    if (!client) return;
    await client.claimUnclaimed();
    await refresh(client);
    if (online) void sync();
  };

  const queued = pending.filter((p) => p.state === 'PENDING');
  const kept = pending.filter((p) => p.state === 'KEPT');
  const items = (template?.template.sections ?? []).flatMap((s) => s.items ?? []);
  return (
    <>
      <PageHeader title={t('field.title')} description={t('field.description')} />
      <Notice tone={online ? 'ok' : 'warn'}>{online ? t('field.online') : t('field.offline')}</Notice>
      {!durable && <Notice tone="warn">{t('field.notDurable')}</Notice>}
      {notice && <Notice tone="warn">{notice}</Notice>}
      {unclaimed > 0 && client && (
        <Notice tone="warn">
          {t('field.unclaimed', { n: unclaimed })}{' '}
          <button type="button" className="link" onClick={() => void claim()}>
            {t('field.claim')}
          </button>
        </Notice>
      )}
      {queued.length > 0 && (
        <Notice tone="warn">
          {t('field.queued', { n: queued.length })}{' '}
          <button type="button" className="link" disabled={!online} onClick={() => void sync()}>
            {t('field.syncNow')}
          </button>
        </Notice>
      )}
      {kept.length > 0 && (
        <Section title={t('field.keptTitle')}>
          <p className="hint">{t('field.keptHint')}</p>
          <ul>
            {kept.map((k) => (
              <li key={k.id}>
                <Badge value={k.code ?? 'REJECTED'} /> {k.target} {k.at && <span className="small muted">{fmtDate(k.at)}</span>}{' '}
                {confirmDiscard === k.id ? (
                  <button type="button" className="link" onClick={() => void discard(k.id)}>
                    {t('field.discardConfirm')}
                  </button>
                ) : (
                  <button type="button" className="link" onClick={() => setConfirmDiscard(k.id)}>
                    {t('field.discard')}
                  </button>
                )}
              </li>
            ))}
          </ul>
        </Section>
      )}
      <ProblemAlert error={visits.error ?? syncErr} onRetry={visits.reload} />
      {visits.loading && <Loading />}
      {!open && (
        <>
          {assignments.length > 0 && (
            <Section title={t('field.assignments')}>
              <ul className="cards">
                {assignments.map((a) => (
                  <li key={String(a.id)} className="card">
                    <h2>{String(a.businessKey ?? a.targetLabel ?? a.id)}</h2>
                    <p>{String(a.address ?? a.targetLabel ?? '')}</p>
                    {Boolean(a.windowStart ?? a.dueAt) && <p className="small">{fmtDate(String(a.windowStart ?? a.dueAt))}</p>}
                    <button type="button" className="primary" onClick={() => start('assignment', String(a.id))}>
                      {t('field.record')}
                    </button>
                  </li>
                ))}
              </ul>
            </Section>
          )}
          {visits.data?.length === 0 && assignments.length === 0 && <Empty message={t('field.empty')} />}
          <ul className="cards">
            {visits.data?.map((v) => (
              <li key={v.id} className="card">
                <h2>{v.businessKey}</h2>
                <p>{v.address}</p>
                {v.scheduledAt && <p className="small">{fmtDate(v.scheduledAt)}</p>}
                <button type="button" className="primary" onClick={() => start('visit', v.id)}>
                  {t('field.record')}
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
      {assignment && (
        <Section title={`${String(assignment.businessKey ?? assignment.targetLabel ?? assignment.id)} — ${String(assignment.address ?? '')}`}>
          {!template && <Loading />}
          {template && (
            <fieldset className="field">
              <legend>{t('field.checklist')}</legend>
              {items.map((it) => (
                <ItemInput key={it.id} item={it} lang={i18n.language} value={answers[it.id]} onChange={(v) => setAnswers({ ...answers, [it.id]: v })} />
              ))}
            </fieldset>
          )}
          <TextArea label={t('field.notes')} value={notes} onChange={setNotes} />
          <div className="toolbar">
            <button type="button" onClick={() => setOpen(null)}>
              {t('common.cancel')}
            </button>
            <button type="button" className="primary" disabled={!template || !client} onClick={() => void saveAssignment()}>
              {t('field.save')}
            </button>
          </div>
        </Section>
      )}
      {visit && (
        <Section title={`${visit.businessKey} — ${visit.address}`}>
          <fieldset className="field">
            <legend>{t('field.checklist')}</legend>
            {visit.checklist.map((c) => (
              <Checkbox key={c.id} label={c.label} checked={Boolean(answers[c.id])} onChange={(v) => setAnswers({ ...answers, [c.id]: v })} />
            ))}
          </fieldset>
          <TextArea label={t('field.notes')} value={notes} onChange={setNotes} />
          <div className="toolbar">
            <button type="button" onClick={() => setOpen(null)}>
              {t('common.cancel')}
            </button>
            <button type="button" className="primary" disabled={!client} onClick={() => void saveVisit()}>
              {t('field.save')}
            </button>
          </div>
        </Section>
      )}
    </>
  );
}
