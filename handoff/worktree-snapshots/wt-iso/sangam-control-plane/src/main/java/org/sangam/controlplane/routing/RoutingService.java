// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.routing;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.sangam.controlplane.cells.Cell;
import org.sangam.controlplane.cells.CellDirectory;
import org.sangam.controlplane.config.ControlPlaneProperties;
import org.sangam.controlplane.registry.Tenant;
import org.sangam.controlplane.registry.TenantRegistry;
import org.springframework.stereotype.Service;

/**
 * Tenant to cell routing (SCALE-ARCHITECTURE.md section 5.4). Gateways cache answers (TTL + route epoch) so the control
 * plane is never on the hot path: a 19,500-tenant snapshot is ~2 MB and is refreshed incrementally.
 */
@Service
public class RoutingService {

    private final TenantRegistry registry;
    private final CellDirectory cells;
    private final ControlPlaneProperties props;

    public RoutingService(TenantRegistry registry, CellDirectory cells, ControlPlaneProperties props) {
        this.registry = registry;
        this.cells = cells;
        this.props = props;
    }

    public Route route(String tenantCode) {
        Tenant t = registry.get(tenantCode);
        Optional<Cell> c = t.cellId() == null ? Optional.empty() : cells.find(t.cellId());
        return toRoute(t, c.orElse(null));
    }

    public List<Route> changedSince(long sinceMs) {
        Map<String, Cell> byId = cells.all().stream().collect(Collectors.toMap(Cell::cellId, c -> c));
        return registry.routesChangedSince(sinceMs).stream().map(t -> toRoute(t, byId.get(t.cellId()))).toList();
    }

    Route toRoute(Tenant t, Cell c) {
        long ttl = props.routeTtl().toSeconds();
        if (t.cellId() == null || c == null) {
            return new Route(t.tenantCode(), t.path(), null, null, "UNPLACED", t.routeEpoch(), ttl, 0);
        }
        String state;
        int retry = 0;
        if (Tenant.DRAINING.equals(t.status())) {
            state = "DRAINING";
            retry = 30;
        } else if (Tenant.MOVING.equals(t.status())) {
            state = "MOVING";
            retry = 60;
        } else if (!c.serving()) {
            state = "CELL_DOWN";
            retry = 30;
        } else {
            state = "ROUTABLE";
        }
        return new Route(t.tenantCode(), t.path(), c.cellId(), c.apiBaseUrl(), state, t.routeEpoch(), ttl, retry);
    }
}
