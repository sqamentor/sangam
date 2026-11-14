#!/usr/bin/env bash
# Trial 2 SD-428 second verification of Sangam push gate (commit 4bd5a2e). LOCAL ONLY: bare remote in scratch.
set -u
SP="$1"; cd "$SP/sg" || exit 9
test "$(git rev-parse HEAD)" = 4bd5a2ef021e8cc5f3dbd4961e2f42c8564ef7bf || { echo WRONG_SHA; exit 9; }
export SANGAM_PUSH_MIRROR_DIR="$SP/mirror"; rm -rf "$SP/mirror" "$SP/bare.git"; rm -f "$(git rev-parse --git-dir)/sangam-push-approvals.jsonl"
git init -q --bare "$SP/bare.git"
R="$SP/bare.git"; git remote remove plantbare 2>/dev/null; git remote add plantbare "$R"
git config core.hooksPath .githooks
git config user.email t2@example.invalid; git config user.name trial2
res(){ echo "RESULT $1: $2"; }
G=tools/governance/push-approval.mjs
echo "== selftest"; node tools/governance/selftest-push-approval.mjs 2>&1 | tail -15
H=$(git rev-parse HEAD)
echo "== P1 no approval"; git push -q plantbare HEAD:refs/heads/main >/dev/null 2>&1 && res P1 "PUSHED (gate missed)" || res P1 "blocked"
echo "== P2 record without --source/--tty"; node $G record --sha $H --remote "$R" --ref refs/heads/main --words "SYNTHETIC TEST APPROVAL ONE" --conversation t2-synthetic >/dev/null 2>&1 && res P2 "recorded (gate missed)" || res P2 "refused"
echo "== P2b record with wrong --source"; node $G record --sha $H --remote "$R" --ref refs/heads/main --words "SYNTHETIC TEST APPROVAL ONE" --conversation t2-synthetic --source agent >/dev/null 2>&1 && res P2b "recorded (gate missed)" || res P2b "refused"
echo "== P3 valid approval"; node $G record --sha $H --remote "$R" --ref refs/heads/main --words "SYNTHETIC TEST APPROVAL ONE" --conversation t2-synthetic --source user-message
git push -q plantbare HEAD:refs/heads/main 2>&1 | tail -2; [ "$(git --git-dir="$R" rev-parse refs/heads/main 2>/dev/null)" = "$H" ] && res P3 "passed once" || res P3 "not pushed"
echo "== P4 reuse (same sha/ref to a fresh ref name is a different ref; reuse = replay hook input)"; printf "HEAD %s refs/heads/main %s\n" $H 0000000000000000000000000000000000000000 | node $G check plantbare "$R" >/dev/null 2>&1 && res P4 "accepted (gate missed)" || res P4 "blocked"
echo "== P5 non-fast-forward"; git checkout -q --detach HEAD~1; echo synthetic > t2-nonff.txt; git add t2-nonff.txt; git commit -q -m "synthetic non-ff"; NF=$(git rev-parse HEAD)
node $G record --sha $NF --remote "$R" --ref refs/heads/main --words "SYNTHETIC TEST APPROVAL TWO" --conversation t2-synthetic --source user-message >/dev/null
git push -q --force plantbare HEAD:refs/heads/main >/dev/null 2>&1; [ "$(git --git-dir="$R" rev-parse refs/heads/main)" = "$H" ] && res P5 "blocked" || res P5 "FORCED (gate missed)"
git checkout -q 4bd5a2ef021e8cc5f3dbd4961e2f42c8564ef7bf
echo "== P6 deletion"; git push -q plantbare :refs/heads/main >/dev/null 2>&1; git --git-dir="$R" rev-parse -q --verify refs/heads/main >/dev/null && res P6 "blocked" || res P6 "DELETED (gate missed)"
echo "== verify before tamper"; node $G verify; echo "verify exit $?"
echo "== P7 mirror deleted"; cp "$SP/mirror/mirror.jsonl" "$SP/mirror.bak"; rm -f "$SP/mirror/mirror.jsonl"; node $G verify >/dev/null 2>&1 && res P7 "verify OK (gate missed)" || res P7 "verify fails"; cp "$SP/mirror.bak" "$SP/mirror/mirror.jsonl"
echo "== P8 one tampered byte"; L="$(git rev-parse --git-dir)/sangam-push-approvals.jsonl"; cp "$L" "$SP/ledger.bak"; python -c "
import sys;p=sys.argv[1];b=bytearray(open(p,'rb').read());i=b.find(b'SYNTHETIC TEST APPROVAL ONE');b[i]=ord('X');open(p,'wb').write(b)" "$L"; node $G verify >/dev/null 2>&1 && res P8 "verify OK (gate missed)" || res P8 "verify fails"
cp "$SP/ledger.bak" "$L"; echo "== P8 real case restored"; node $G verify; echo "verify exit $?"
git config --unset core.hooksPath; git remote remove plantbare
