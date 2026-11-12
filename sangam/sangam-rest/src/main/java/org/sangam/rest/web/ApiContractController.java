// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.rest.port.ApiContractPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M-068 (R43), tag ApiContracts: putApiContract ({@value Permissions#PACKAGE_AUTHOR}), listApiContracts, getApiContract
 * and listApiContractOperations ({@value Permissions#PACKAGE_READ}). The request body of a put is the OpenAPI 3.x or
 * AsyncAPI 2.x / 3.x document itself, a JSON object; versions are immutable and there is no delete.
 */
@RestController
@RequestMapping("/api/v1/packages")
public class ApiContractController {

    private final ApiContractPort contracts;
    private final PermissionPort permissions;

    public ApiContractController(ApiContractPort contracts, PermissionPort permissions) {
        this.contracts = contracts;
        this.permissions = permissions;
    }

    @PutMapping("/{id}/contracts/{contractId}")
    public ResponseEntity<ApiContractPort.ApiContractSummary> putApiContract(RequestContext ctx, @PathVariable String id,
            @PathVariable String contractId, @RequestBody(required = false) JsonNode body) {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        if (body == null || !body.isObject()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "the request body must be the contract document, a JSON object")
                    .with("field", "body");
        }
        // the resource reference is the package id, as on the other package commands
        return Lists.okRef(id, contracts.put(ctx, id, contractId, body));
    }

    @GetMapping("/{id}/contracts")
    public ResponseEntity<List<ApiContractPort.ApiContractSummary>> listApiContracts(RequestContext ctx,
                                                                                    @PathVariable String id) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        return Lists.ok(contracts.list(ctx, id));
    }

    @GetMapping("/{id}/contracts/{contractId}")
    public ApiContractPort.ApiContract getApiContract(RequestContext ctx, @PathVariable String id,
            @PathVariable String contractId, @RequestParam(required = false) Integer version) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        return contracts.get(ctx, id, contractId, version);
    }

    @GetMapping("/{id}/contracts/{contractId}/operations")
    public ResponseEntity<List<ApiContractPort.ContractOperation>> listApiContractOperations(RequestContext ctx,
            @PathVariable String id, @PathVariable String contractId, @RequestParam(required = false) Integer version) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        return Lists.ok(contracts.operations(ctx, id, contractId, version));
    }
}
