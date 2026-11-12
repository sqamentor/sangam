# SPDX-License-Identifier: Apache-2.0
# M-021 / C3-13: test-mode clock steering (W3-K 12:23:57Z, W3-F 13:24:47Z, integrator 12:26Z). The routes exist only
# when sangam.test-mode.enabled is true (default false; the engine refuses to start in test mode unless the database
# is H2 and no profile is prod/production/live), and answer 404 otherwise. The clock is instance-wide, so the caller
# is a platform administrator (group sangam-admin). Backed by org.sangam.engine.clock.TestModeClock: the engine clock
# and the business clock move together; every change is audited as TEST_CLOCK_CHANGED before it is answered and is
# undone when the audit write fails.
TAGS.append(("TestMode", "Test-mode clock steering (M-021); present only on a test-mode server"))
schema("TestClock","The test-mode clock (TestModeClock): the instant the engine and business clocks now read and their "
       "offset from the wall clock; `action` is set on a change.",
       "now:dt offsetMs:long action", ["now", "offsetMs"], enums={"action": ["ADVANCE", "SET", "RESET"]})
schema("TestClockChange", "Exactly one of advanceBy (a positive ISO-8601 duration), set (an instant) or reset (true); "
       "reason is recorded in the TEST_CLOCK_CHANGED audit event.", "advanceBy set:dt reset:bool reason",
       descs={"advanceBy": "Positive ISO-8601 duration, e.g. P3D or PT2H"})
_TC = "/test-mode/clock"
_TC_REQ = ["M-021", "C3-13", "R42"]
_TC_ERR = ("Unauthorized", "Forbidden", "NotFound")
op("get", _TC, "getTestClock", "TestMode", "Read the test-mode clock",
   "The clock the engine and the business calendar read. 404 unless test mode is on (sangam.test-mode.enabled).",
   _TC_REQ, resp=(200, "TestClock"), perm="admin.read", errors=_TC_ERR)
op("post", _TC, "changeTestClock", "TestMode", "Move the test-mode clock",
   "Advances, sets or resets the engine and business clocks together (timers due by the new time fire on the next job "
   "acquisition). Each change is audited as TEST_CLOCK_CHANGED {action, previousOffsetMs, offsetMs, now, actor, reason} "
   "before it is answered; a failed audit write undoes it. 400 VALIDATION_FAILED for no action, several actions or a "
   "non-positive advanceBy; 404 unless test mode is on.", _TC_REQ, body="TestClockChange", resp=(200, "TestClock"),
   cmd="test-mode.clock.change", perm="admin.write", errors=("BadRequest",) + _TC_ERR)
for _m in ("get", "post"):
    PATHS[_TC][_m]["x-sangam-groups"] = ["sangam-admin"]
