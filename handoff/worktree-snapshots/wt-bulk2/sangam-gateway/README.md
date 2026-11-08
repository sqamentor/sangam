# sangam-gateway (SCALE)

Reactive routing edge (port 8000, management 8001). Design: `../SCALE-ARCHITECTURE.md` sections 4 and 5.5.

| File | Covers |
|---|---|
| `TenantResolver.java` | Tenant from JWT claim → host `<tenant><suffix>` → `X-Sangam-Tenant` (routing only; the cell authorizes, R19) |
| `RouteCache.java` | Route cache from the control plane: TTL + route epoch, incremental snapshot every 5 s, sticky stale-if-error up to 24 h |
| `CellProxy.java` | Streaming proxy; request-id propagation; 503 + Retry-After for DRAINING writes, MOVING, CELL_DOWN, unreachable cell, 421 (moved); 429 per-tenant in-flight cap |
| `GatewayRoutes.java` | Catch-all route (actuator excluded), pooled Reactor Netty client |

Why not Spring Cloud Gateway: it needs the Spring Cloud BOM, and the parent POM allows only the Spring Boot BOM.

Dependencies: spring-boot-starter-webflux / actuator 4.1.2, Reactor Netty, Micrometer Prometheus (Apache-2.0);
sangam-cell (internal).
