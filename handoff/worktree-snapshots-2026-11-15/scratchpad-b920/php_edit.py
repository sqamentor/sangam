import io
import os
import sys

os.chdir(sys.argv[1])


def edit(p, pairs):
    s = io.open(p, encoding='utf-8', newline='').read()
    nl = '\r\n' if '\r\n' in s else '\n'
    for old, new in pairs:
        o = old.replace('\n', nl)
        n = new.replace('\n', nl)
        assert s.count(o) == 1, (p, old, s.count(o))
        s = s.replace(o, n)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)


edit('src/Model/ExternalWork.php', [
(""" * $priority is int64 (PHP int). The properties are exactly sangam-api.yaml ExternalWork (none is required on the
 * wire; fromArray() decodes an absent lockVersion as 0 and absent variables as []). The server sends no per-item""",
 """ * $priority is int64 (PHP int). The properties are exactly sangam-api.yaml ExternalWork; none is required on the
 * wire, so every one is nullable and an absent member stays null (no defaults). The worker starts an item only when
 * id, lockVersion and attemptId are present (A-1). The server sends no per-item"""),
("""    /** @param array<string, mixed> $variables */
    public function __construct(""", """    /** @param array<string, mixed>|null $variables */
    public function __construct("""),
("""        public readonly array $variables,
        public readonly int $lockVersion,""", """        public readonly ?array $variables,
        public readonly ?int $lockVersion,"""),
("""            isset($d['variables']) && is_array($d['variables']) ? $d['variables'] : [],
            $i('lockVersion') ?? 0,""", """            isset($d['variables']) && is_array($d['variables']) ? $d['variables'] : null,
            $i('lockVersion'),"""),
])

edit('src/Model/LockState.php', [
("""        public readonly bool $cancelRequested,""", """        public readonly ?bool $cancelRequested,"""),
("""            return new self(null, null, null, false);""", """            return new self(null, null, null, null);"""),
("""            (bool) ($d['cancelRequested'] ?? false),""", """            isset($d['cancelRequested']) ? (bool) $d['cancelRequested'] : null,"""),
])

edit('src/Worker/SangamWorker.php', [
("""        if ($w->id === null || $w->attemptId === null) {
            // A-1: a handler starts only after the LOCKED confirmation with lockVersion + attemptId""",
 """        if ($w->id === null || $w->attemptId === null || $w->lockVersion === null) {
            // A-1: a handler starts only after the LOCKED confirmation with lockVersion + attemptId"""),
("""        if ($w->id === null) {
            return;
        }
        $lv = $lockVersion >= 0 ? $lockVersion : $w->lockVersion;""", """        $lv = $lockVersion >= 0 ? $lockVersion : $w->lockVersion;
        if ($w->id === null || $lv === null) {
            // without the work id and the fencing token the server cannot accept an unlock; the lease expires
            $this->log->warning('work.unlock.skipped', 'unlock impossible without id and lockVersion; lease will expire',
                ['workerId' => $this->workerId, 'workId' => $w->id]);
            return;
        }"""),
("""                self::sendLockState($c['pipe'], $c['lockVersion'], $r->lockExpiresAt, $r->cancelRequested);
                if ($r->cancelRequested && !$c['cancelSent']) {""", """                self::sendLockState($c['pipe'], $c['lockVersion'], $r->lockExpiresAt, $r->cancelRequested === true);
                if ($r->cancelRequested === true && !$c['cancelSent']) {"""),
("""                $ctx->markExtended(Time::parse($r->lockExpiresAt), $r->lockVersion, $r->cancelRequested);
                if ($r->cancelRequested) {""", """                $ctx->markExtended(Time::parse($r->lockExpiresAt), $r->lockVersion, $r->cancelRequested === true);
                if ($r->cancelRequested === true) {"""),
])

edit('src/Worker/TaskContext.php', [
("""        $this->lockVersion = $work->lockVersion;""", """        if ($work->id === null || $work->attemptId === null || $work->lockVersion === null) {
            // A-1: a handler runs only after the LOCKED confirmation (work id, lockVersion and attemptId)
            throw new \\InvalidArgumentException('work item without its LOCKED confirmation (id, lockVersion, attemptId)');
        }
        $this->lockVersion = $work->lockVersion;"""),
("""    /** @return array<string, mixed> */
    public function variables(): array
    {
        return $this->work->variables;
    }""", """    /** @return array<string, mixed> the VariableMap as sent ([] when the server sent none) */
    public function variables(): array
    {
        return $this->work->variables ?? [];
    }"""),
("""        if (!array_key_exists($name, $this->work->variables)) {
            return $default;
        }
        $v = TypedValue::unwrap($this->work->variables[$name]);""", """        $vars = $this->variables();
        if (!array_key_exists($name, $vars)) {
            return $default;
        }
        $v = TypedValue::unwrap($vars[$name]);"""),
])

edit('src/Worker/TopicSubscription.php', [
("""    /** TopicRequest body for fetch-and-lock. @return array<string, mixed> */
    public function toTopicRequest(): array""", """    /**
     * TopicRequest body for fetch-and-lock.
     *
     * @deprecated since 0.1.0; use {@see topicRequest()}, the typed TopicRequest (same wire members).
     * @return array<string, mixed>
     */
    public function toTopicRequest(): array"""),
])
print('ok')
