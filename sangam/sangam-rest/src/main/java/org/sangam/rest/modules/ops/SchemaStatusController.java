// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ops;

import java.sql.SQLException;

import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.TxSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M-092: {@code GET /api/v1/ops/schema-status} (operationId {@code getSchemaStatus}, permission {@code ops.read}):
 * applied product changesets, the engine schema version and the Liquibase lock, read only. {@code sangam upgrade check
 * --server} compares it with the target release's changelog. The read itself is {@link SchemaStatusReader}.
 */
@RestController
@RequestMapping("/api/v1")
public class SchemaStatusController {

    private final PermissionPort permissions;
    private final TxSupport tx;

    public SchemaStatusController(PermissionPort permissions, TxSupport tx) {
        this.permissions = permissions;
        this.tx = tx;
    }

    @GetMapping("/ops/schema-status")
    public SchemaStatusReader.Status status(RequestContext ctx) {
        permissions.require(ctx, Permissions.OPS_READ);
        return tx.withConnection(c -> {
            try {
                return SchemaStatusReader.read(c);
            } catch (SQLException e) {
                throw new IllegalStateException("cannot read the schema status: " + e.getMessage(), e);
            }
        });
    }
}
