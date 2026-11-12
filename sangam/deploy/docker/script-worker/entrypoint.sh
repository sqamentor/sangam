#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Entry point of the script-worker container (M-061, ADR-D-8 script sandbox). cgroup v2 allows controllers in a
# group's subtree only while the group itself holds no process, so the worker first moves itself (and everything
# else in the container's cgroup namespace root) into a leaf group "worker", then enables memory, pids and cpu for
# the subtree. nsjail then creates one sibling group per script with the limits of NsjailSandbox. The same pattern
# runs Docker inside Docker. The container needs its own writable cgroup namespace (tools/sangam-script-worker/
# README.md, section "Container privileges").
set -eu

CG=/sys/fs/cgroup
if [ ! -f "$CG/cgroup.controllers" ]; then
  echo "script-worker: cgroup v2 is not mounted at $CG; refusing to run scripts without limits" >&2
  exit 70
fi
if ! mkdir -p "$CG/worker" 2>/dev/null || [ ! -w "$CG/cgroup.subtree_control" ]; then
  echo "script-worker: $CG is not writable; run with a private writable cgroup namespace (see README)" >&2
  exit 70
fi
# move every process of the namespace root into the leaf group (retry: a process may start while we move)
for _ in 1 2 3; do
  while read -r pid; do
    echo "$pid" > "$CG/worker/cgroup.procs" 2>/dev/null || true
  done < "$CG/cgroup.procs"
done
for c in memory pids cpu; do
  if ! grep -qw "$c" "$CG/cgroup.controllers"; then
    echo "script-worker: cgroup controller $c is not available; refusing to run scripts without limits" >&2
    exit 70
  fi
  echo "+$c" > "$CG/cgroup.subtree_control"
done

exec java ${JAVA_OPTS:-} -jar /opt/sangam/script-worker/sangam-script-worker.jar "$@"
