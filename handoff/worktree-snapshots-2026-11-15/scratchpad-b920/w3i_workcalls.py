import os, sys
os.chdir(sys.argv[1])


def edit(p, pairs):
    s = open(p, encoding='utf-8').read()
    for a, b in pairs:
        assert s.count(a) == 1, (p, a)
        s = s.replace(a, b)
    open(p, 'w', encoding='utf-8', newline='\n').write(s)


edit('typescript/test/work-step-problems.test.ts', [
("""// SL-174: step routes on work that is not open answer exactly what the server answers. The expected problems come from
// sdks/testing/work-step-problems.json, which the server's WorkStepJournalH2Test checks against OperatonWorkAdapter
// too, so the engine and the server are compared through one table.""",
"""// SL-174: step routes and lease calls on work that is not open answer exactly what the server answers. The expected
// problems come from sdks/testing/work-step-problems.json, which the server's WorkStepJournalH2Test and
// WorkCallProblemsVectorH2Test check against the server work adapters too, so the engine and the server are compared
// through one table."""),
("""interface Vectors { stepName: string; unknownWorkId: string; cases: Json[] }""",
"""interface Vectors { stepName: string; unknownWorkId: string; cases: Json[]; workCalls: Json[] }"""),
("""const FIXED = new Set(['id', 'scenario', 'status', 'code']);""",
"""const FIXED = new Set(['id', 'scenario', 'call', 'status', 'code']);
const CALL_BODY: Record<string, Json> = {
  complete: { variables: {} },
  fail: { errorMessage: 'again', retries: 0 },
  'bpmn-error': { errorCode: 'E1' },
  unlock: {},
  'extend-lock': { newDurationMs: 60_000 },
  'outcome-unknown': { detail: 'unsure' },
};"""),
("""  it('reads every vector', () => assert.ok(VECTORS.cases.length >= 4));""",
"""  for (const c of VECTORS.workCalls) {
    it(`${String(c.id)}: ${String(c.status)} ${String(c.code)} as on the server`, () => {
      const e = new SangamTestEngine();
      const wid = e.addWork('pay');
      const w = (e.handle('POST', '/work/fetch-and-lock', undefined,
        { workerId: 'w1', topics: [{ name: 'pay', lockDurationMs: 60_000 }], maxTasks: 1 }).body as Json[])[0]!;
      const lease = { workerId: 'w1', lockVersion: w.lockVersion, attemptId: w.attemptId };
      assert.equal(c.scenario, 'INCIDENT', 'known scenario');
      assert.equal(e.handle('POST', `/work/${wid}/fail`, undefined, { ...lease, errorMessage: 'no retries left', retries: 0 }).status, 204);
      assert.equal(e.state(wid), 'INCIDENT');
      const call = String(c.call);
      const body = CALL_BODY[call];
      assert.ok(body, `known call ${call}`);
      expectProblem(c, call, e.handle('POST', `/work/${wid}/${call}`, undefined, { ...lease, ...body }));
    });
  }

  it('reads every vector', () => {
    assert.ok(VECTORS.cases.length >= 4);
    assert.ok(VECTORS.workCalls.length >= 6);
  });"""),
])

edit('python/tests/test_work_step_problems.py', [
('''"""SL-174: step routes on work that is not open answer exactly what the server answers.

The expected problems come from ``sdks/testing/work-step-problems.json``, which the server's ``WorkStepJournalH2Test``
checks against ``OperatonWorkAdapter`` too, so the engine and the server are compared through one table."""''',
'''"""SL-174: step routes and lease calls on work that is not open answer exactly what the server answers.

The expected problems come from ``sdks/testing/work-step-problems.json``, which the server's ``WorkStepJournalH2Test``
and ``WorkCallProblemsVectorH2Test`` check against the server work adapters too, so the engine and the server are
compared through one table."""'''),
('''FIXED = {"id", "scenario", "status", "code"}''',
'''FIXED = {"id", "scenario", "call", "status", "code"}
CALL_BODY: dict[str, dict[str, Any]] = {
    "complete": {"variables": {}},
    "fail": {"errorMessage": "again", "retries": 0},
    "bpmn-error": {"errorCode": "E1"},
    "unlock": {},
    "extend-lock": {"newDurationMs": 60_000},
    "outcome-unknown": {"detail": "unsure"},
}'''),
('''def test_every_vector_is_read() -> None:
    assert len(VECTORS["cases"]) >= 4''',
'''@pytest.mark.parametrize("case", VECTORS["workCalls"], ids=[c["id"] for c in VECTORS["workCalls"]])
def test_lease_calls_on_work_that_is_not_open_answer_the_servers_problem(case: dict[str, Any]) -> None:
    e = SangamTestEngine()
    wid = e.add_work("pay")
    _, _, fetched, _ = e.handle("POST", "/work/fetch-and-lock", None,
                                {"workerId": "w1", "topics": [{"name": "pay"}], "maxTasks": 1})
    w = fetched[0]
    lease = {"workerId": "w1", "lockVersion": w["lockVersion"], "attemptId": w["attemptId"]}
    assert case["scenario"] == "INCIDENT"
    assert e.handle("POST", f"/work/{wid}/fail", None, {**lease, "errorMessage": "no retries left", "retries": 0})[0] == 204
    assert e.state(wid) == "INCIDENT"
    call = case["call"]
    assert call in CALL_BODY
    status, _, body, _ = e.handle("POST", f"/work/{wid}/{call}", None, {**lease, **CALL_BODY[call]})
    _expect(case, call, status, body)


def test_every_vector_is_read() -> None:
    assert len(VECTORS["cases"]) >= 4
    assert len(VECTORS["workCalls"]) >= 6'''),
])

edit('php/tests/WorkStepProblemsVectorTest.php', [
(""" * SL-174: step routes on work that is not open answer exactly what the server answers. The expected problems come
 * from sdks/testing/work-step-problems.json, which the server's WorkStepJournalH2Test checks against
 * OperatonWorkAdapter too, so the engine and the server are compared through one table.""",
""" * SL-174: step routes and lease calls on work that is not open answer exactly what the server answers. The expected
 * problems come from sdks/testing/work-step-problems.json, which the server's WorkStepJournalH2Test and
 * WorkCallProblemsVectorH2Test check against the server work adapters too, so the engine and the server are compared
 * through one table."""),
("""    private const FIXED = ['id', 'scenario', 'status', 'code'];""",
"""    private const FIXED = ['id', 'scenario', 'call', 'status', 'code'];
    private const CALL_BODY = [
        'complete' => ['variables' => []],
        'fail' => ['errorMessage' => 'again', 'retries' => 0],
        'bpmn-error' => ['errorCode' => 'E1'],
        'unlock' => [],
        'extend-lock' => ['newDurationMs' => 60000],
        'outcome-unknown' => ['detail' => 'unsure'],
    ];"""),
("""    public function testEveryVectorIsRead(): void
    {
        self::assertGreaterThanOrEqual(4, count(self::vectors()['cases']));
    }""",
"""    /** @return array<string, array{0: array<string, mixed>}> */
    public static function workCalls(): array
    {
        $out = [];
        foreach (self::vectors()['workCalls'] as $c) {
            $out[(string) $c['id']] = [$c];
        }
        return $out;
    }

    /** @param array<string, mixed> $case */
    #[DataProvider('workCalls')]
    public function testLeaseCallsOnWorkThatIsNotOpenAnswerTheServersProblem(array $case): void
    {
        $e = new SangamTestEngine();
        $wid = $e->addWork('pay');
        [$s, , $fetched] = $e->handle('POST', '/work/fetch-and-lock', null,
            ['workerId' => 'w1', 'topics' => [['name' => 'pay']]]);
        self::assertSame(200, $s);
        $w = $fetched[0];
        $lease = ['workerId' => 'w1', 'lockVersion' => $w['lockVersion'], 'attemptId' => $w['attemptId']];
        self::assertSame('INCIDENT', $case['scenario']);
        self::assertSame(204, $e->handle('POST', '/work/' . $wid . '/fail', null,
            $lease + ['errorMessage' => 'no retries left', 'retries' => 0])[0]);
        self::assertSame('INCIDENT', $e->state($wid));
        $call = (string) $case['call'];
        self::assertArrayHasKey($call, self::CALL_BODY);
        [$s, , $body] = $e->handle('POST', '/work/' . $wid . '/' . $call, null, $lease + self::CALL_BODY[$call]);
        self::expectProblem($case, $call, $s, $body);
    }

    public function testEveryVectorIsRead(): void
    {
        self::assertGreaterThanOrEqual(4, count(self::vectors()['cases']));
        self::assertGreaterThanOrEqual(6, count(self::vectors()['workCalls']));
    }"""),
])
print("ok")
