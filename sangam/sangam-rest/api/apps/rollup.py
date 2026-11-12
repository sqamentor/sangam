# SPDX-License-Identifier: Apache-2.0
# M-017: sangam-rollup REST API (org.sangam.rollup.RollupController, RollupConfig security). The ingest body is the
# roll-up CloudEvent (org.sangam.cell.rollup.RollupEnvelope, ROLLUP_CLOUDEVENT in gen_openapi.py, shared with the
# AsyncAPI channel rollup:national). The response maps are built in the controller; AppContractDriftTest
# (sangam-rest) checks the mappings, parameters and response keys against this contract.
app("sangam-rollup-api.yaml", "Sangam roll-up API",
    "National cross-cell read model: CloudEvent ingest from the cells and dashboard queries (SCALE-ARCHITECTURE section 8)",
    "The roll-up's REST API (port 8095, base path /api/v1). Cells post the roll-up stream (at least once; the roll-up "
    "deduplicates on the CloudEvents `id`); dashboards read totals, daily series, drill-downs and feed freshness. A "
    "hierarchy node is a tenant path such as `IN/RJ/RJ-JPR`; day ranges are `yyyyMMdd` UTC, both ends included.",
    {"ADMIN": "everything", "CELL": "ingest", "DASHBOARD": "queries"},
    [("Ingest", "The roll-up stream from the cells (IR-3)"), ("Queries", "Dashboard reads (counts and identifiers only)")],
    servers=[{"url": "/api/v1", "description": "Sangam roll-up"}],
    errors={"BadRequest": "Invalid request. A rule the input breaks (for example a batch larger than "
                          "`sangam.rollup.max-batch`, default 500) answers problem+json; a missing or mistyped query "
                          "parameter answers Spring Boot's default error body."})

SCHEMAS["RollupCloudEvent"] = ROLLUP_CLOUDEVENT
schema("IngestResult", "Counts of one ingest call.", "projected:int duplicates:int rejected:int")
schema("RollupMetrics", "Totals per metric for one node over a day range.", "node from:int to:int metrics:lmap")
schema("RollupSeries", "Daily totals of one metric for one node; `series` is keyed by the day (`yyyyMMdd`).",
       "node metric series:lmap")
schema("RollupChild", "One child node with the metric's total.", "node value:long")
schema("RollupChildren", "Children of a node (nation to states, state to districts ...) with one metric's total.",
       "node metric children:list:RollupChild")
schema("RollupFeed", "Feed freshness of one cell: events received, the newest event time and the lag behind now.",
       "cellId events:long lastEventAtMs:long lagMs:long")

NODE = q("node", desc="Hierarchy node (tenant path), e.g. IN/RJ", required=True)
METRIC = q("metric", desc="Metric name", required=True)
FROM = q("from", "int", "First day, yyyyMMdd (UTC)", required=True)
TO = q("to", "int", "Last day, yyyyMMdd (UTC), included", required=True)
ERR_Q = ("BadRequest", "Unauthorized", "Forbidden")

aop("post", "/ingest", "ingestRollupEvents", "Ingest", "Ingest roll-up events",
    "One structured CloudEvent or a JSON array of them. Each event is projected at most once (deduplicated on `id`). "
    "202 with the counts; a single event that is rejected answers 422 with the counts, which the cell's relay treats "
    "as a poison event.", ("CELL", "ADMIN"),
    body={"oneOf": [R("RollupCloudEvent"), {"type": "array", "items": R("RollupCloudEvent")}]},
    body_ct=("application/cloudevents+json", "application/json"), resp=(202, "IngestResult"), errors=ERR_Q,
    more={"422": {"description": "The single event was rejected (a required attribute is missing or too long)",
                  "content": {"application/json": {"schema": R("IngestResult")}}}})
aop("get", "/rollup/metrics", "getRollupMetrics", "Queries", "Totals per metric",
    "Totals per metric for one node over a day range.", ("DASHBOARD", "ADMIN"), params=[NODE, FROM, TO],
    resp=(200, "RollupMetrics"), errors=ERR_Q)
aop("get", "/rollup/series", "getRollupSeries", "Queries", "Daily series",
    "Daily totals of one metric for one node.", ("DASHBOARD", "ADMIN"), params=[NODE, METRIC, FROM, TO],
    resp=(200, "RollupSeries"), errors=ERR_Q)
aop("get", "/rollup/children", "getRollupChildren", "Queries", "Drill-down",
    "The node's children with one metric's total (0 for a child without events).", ("DASHBOARD", "ADMIN"),
    params=[NODE, METRIC, FROM, TO], resp=(200, "RollupChildren"), errors=ERR_Q)
aop("get", "/rollup/feeds", "listRollupFeeds", "Queries", "Feed freshness per cell",
    "A stale cell shows up here before anyone reads a wrong national number.", ("DASHBOARD", "ADMIN"),
    resp=(200, "array:RollupFeed"), errors=("Unauthorized", "Forbidden"))
