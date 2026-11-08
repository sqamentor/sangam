# Dual-region reference architecture (NG-39)

**Status: design only. Nothing here has been deployed or tested.** It extends `README.md` §4.1 (failover runbook) and the `sangam-cell` cell descriptors.

## Decision

Run **active-passive per cell, active-active across cells**. Each cell has one writable primary region. Different cells may have their primaries in different regions, so both regions serve traffic. A single cell is not written from two regions at once, because lease fencing, the operation ledger and the audit chain all need one serial order per root.

```
                  control plane + gateway (both regions)
                     /                         \
        Region A                                Region B
   cell-1 PRIMARY  ── async or sync replica ──▶ cell-1 STANDBY
   cell-2 STANDBY  ◀── async or sync replica ── cell-2 PRIMARY
```

## Replication choice per cell

| Mode | RPO | Cost | Use when |
|---|---|---|---|
| Synchronous database replication | 0 | Write latency rises by the inter-region round trip | Regions are close, typically under 10 ms |
| Asynchronous | Seconds; loss is possible | No write penalty | Regions are far apart |

Document stores replicate separately; a document may lag its database row. The post-restore checks must treat a missing object as an incident.

## Failover

1. Fence the old primary first: stop its server pods, or revoke its database access. Without this, two writers can exist.
2. Promote the standby database.
3. Start one server pod with `SANGAM_RECONCILE_ON_START=true` and `SANGAM_JOB_EXECUTOR_ENABLED=false`, and run the post-restore checks from `README.md` §4.3.
4. After **asynchronous** failover, treat recent external effects as unknown: connector calls near the cut-over may have happened with no ledger row. Reconcile them against the downstream system before enabling the job executor.
5. Switch the cell's route at the gateway.

## Not provided

- Automatic failover and a split-brain arbiter. Failover is a human decision, using the runbook.
- Multi-writer on one cell.
- Measured RPO, RTO or latency. Each deployment must measure them and record the results in `deploy/evidence/`.
- Helm values for a cross-region database: replication is run by the database operator or service in use.
