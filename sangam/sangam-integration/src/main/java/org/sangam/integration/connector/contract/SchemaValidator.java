// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.contract;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * M-068: validates a request or message body against the schema of a contract operation
 * ({@link ContractOperations.Operation#requestSchema()}). {@code document} is the whole contract, for a recursive
 * {@code $ref} left in the schema. Answers the violations, empty when the instance is valid; never throws for an invalid
 * instance (a schema it cannot use safely, such as one that refers outside the contract, is an
 * {@link IllegalArgumentException}). The implementation is {@link JsonSchemaValidator} (networknt json-schema-validator,
 * Apache-2.0); {@link JsonSchemaValidator#forContract} picks the schema dialect of the contract.
 */
@FunctionalInterface
public interface SchemaValidator {

    List<String> validate(JsonNode schema, JsonNode document, JsonNode instance);
}
