// SPDX-License-Identifier: Apache-2.0
package org.sangam.rollup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sangam.cell.rollup.MiniJson;
import org.sangam.cell.rollup.RollupEnvelope;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingest ({@code POST /api/v1/ingest}: one structured CloudEvent or a JSON array of them) and dashboard queries
 * ({@code /api/v1/rollup/**}). The hierarchy node is passed as {@code node=IN/RJ/RJ-JPR}. Day ranges are
 * {@code yyyyMMdd} UTC. Every query reads at most {@code shards x metrics x days} rows of one node (index range scan).
 */
@RestController
@RequestMapping("/api/v1")
public class RollupController {

    private final RollupProjector projector;
    private final RollupProperties props;
    private final JdbcTemplate jdbc;

    public RollupController(RollupProjector projector, RollupProperties props, JdbcTemplate jdbc) {
        this.projector = projector;
        this.props = props;
        this.jdbc = jdbc;
    }

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody String body) {
        String trimmed = body == null ? "" : body.trim();
        List<String> events = new ArrayList<>();
        if (trimmed.startsWith("[")) {
            Object parsed = MiniJson.read(trimmed);
            for (Object o : (List<?>) parsed) {
                events.add(MiniJson.write(o));
            }
        } else {
            events.add(trimmed);
        }
        if (events.size() > props.maxBatch()) {
            throw new IllegalArgumentException("batch larger than " + props.maxBatch());
        }
        int projected = 0;
        int duplicates = 0;
        int rejected = 0;
        for (String json : events) {
            switch (projector.project(RollupEnvelope.parse(json))) {
                case PROJECTED -> projected++;
                case DUPLICATE -> duplicates++;
                case REJECTED -> rejected++;
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("projected", projected);
        r.put("duplicates", duplicates);
        r.put("rejected", rejected);
        // a single rejected event is a poison event for the relay (4xx); a batch reports counts
        HttpStatus status = events.size() == 1 && rejected == 1 ? HttpStatus.UNPROCESSABLE_CONTENT : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(r);
    }

    /** Totals per metric for one node over a day range. */
    @GetMapping("/rollup/metrics")
    public Map<String, Object> metrics(@RequestParam String node, @RequestParam int from, @RequestParam int to) {
        Map<String, Object> totals = new LinkedHashMap<>();
        jdbc.query("SELECT metric, SUM(counter_value) FROM RU_COUNTER WHERE node_path = ? AND day_bucket BETWEEN ? AND ?"
                + " GROUP BY metric ORDER BY metric", rs -> {
                    totals.put(rs.getString(1), rs.getLong(2));
                }, node, from, to);
        return Map.of("node", node, "from", from, "to", to, "metrics", totals);
    }

    /** Daily series of one metric for one node. */
    @GetMapping("/rollup/series")
    public Map<String, Object> series(@RequestParam String node, @RequestParam String metric, @RequestParam int from,
                                      @RequestParam int to) {
        Map<String, Object> days = new LinkedHashMap<>();
        jdbc.query("SELECT day_bucket, SUM(counter_value) FROM RU_COUNTER WHERE node_path = ? AND metric = ?"
                + " AND day_bucket BETWEEN ? AND ? GROUP BY day_bucket ORDER BY day_bucket", rs -> {
                    days.put(Integer.toString(rs.getInt(1)), rs.getLong(2));
                }, node, metric, from, to);
        return Map.of("node", node, "metric", metric, "series", days);
    }

    /** Drill-down: children of a node (nation to states, state to districts ...) with one metric's total. */
    @GetMapping("/rollup/children")
    public Map<String, Object> children(@RequestParam String node, @RequestParam String metric, @RequestParam int from,
                                        @RequestParam int to) {
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbc.query("SELECT n.node_path, COALESCE(SUM(c.counter_value), 0) FROM RU_NODE n LEFT JOIN RU_COUNTER c"
                + " ON c.node_path = n.node_path AND c.metric = ? AND c.day_bucket BETWEEN ? AND ?"
                + " WHERE n.parent_path = ? GROUP BY n.node_path ORDER BY n.node_path", rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("node", rs.getString(1));
                    m.put("value", rs.getLong(2));
                    rows.add(m);
                }, metric, from, to, node);
        return Map.of("node", node, "metric", metric, "children", rows);
    }

    /** Per-cell feed freshness (a stale cell shows up here before anyone reads a wrong national number). */
    @GetMapping("/rollup/feeds")
    public List<Map<String, Object>> feeds() {
        List<Map<String, Object>> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        jdbc.query("SELECT cell_id, SUM(events), MAX(last_event_at_ms) FROM RU_CELL_FEED GROUP BY cell_id ORDER BY cell_id",
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("cellId", rs.getString(1));
                    m.put("events", rs.getLong(2));
                    m.put("lastEventAtMs", rs.getLong(3));
                    m.put("lagMs", now - rs.getLong(3));
                    rows.add(m);
                });
        return rows;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail bad(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
