// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * M-068 (R43): the OpenAPI / AsyncAPI contracts of a package ({@code /packages/{id}/contracts}, tag ApiContracts). A
 * stored version is immutable; storing a changed document makes the next version, storing the same document answers the
 * stored version. There is no status and no delete. Every method answers 404 for a package the caller's tenant does not
 * have, and for a contract or version that is not stored.
 */
public interface ApiContractPort {

    /** {@code ApiContractSummary}: one stored version without its document. format: OPENAPI | ASYNCAPI. */
    record ApiContractSummary(String tenantId, String packageId, String contractId, int version, String format,
                              String specVersion, String sha256) {
    }

    /** {@code ApiContract}: one stored version with its document. */
    record ApiContract(String tenantId, String packageId, String contractId, int version, String format,
                       String specVersion, String sha256, JsonNode document) {
    }

    /** {@code ContractOperation}: method and path (OpenAPI) or action and channel (AsyncAPI), schemas inlined. */
    record ContractOperation(String operationId, String format, String method, String path, String action,
                             String channel, String summary, JsonNode requestSchema, Map<String, JsonNode> responseSchemas,
                             List<String> servers) {
    }

    /**
     * putApiContract: 400 VALIDATION_FAILED for an invalid contract id or a document that is not a usable contract; 409
     * INVALID_TRANSITION unless the package is DRAFT (owner decision 115). A new version is audited (API_CONTRACT_STORED)
     * in the same transaction; an identical re-send answers the stored version and audits nothing.
     */
    ApiContractSummary put(RequestContext ctx, String packageId, String contractId, JsonNode document);

    /** listApiContracts: the latest version of each contract, by contract id. */
    List<ApiContractSummary> list(RequestContext ctx, String packageId);

    /** getApiContract: {@code version} null = the latest. */
    ApiContract get(RequestContext ctx, String packageId, String contractId, Integer version);

    /** listApiContractOperations: in document order; {@code version} null = the latest. */
    List<ContractOperation> operations(RequestContext ctx, String packageId, String contractId, Integer version);
}
