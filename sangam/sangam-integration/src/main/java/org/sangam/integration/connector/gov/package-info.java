// SPDX-License-Identifier: Apache-2.0
/**
 * Connectors to Indian government platforms (eSign, DigiLocker push and requester, Aadhaar sub-AUA), each built against
 * the platform's public behaviour with a loopback simulator in {@code org.sangam.integration.sim} (ADR-D-13).
 *
 * <h2>API Setu publication note (M-072)</h2>
 * API Setu is the Government of India API gateway through which partner APIs such as DigiLocker are published to
 * requesters. Nothing in this package hard-codes a government endpoint:
 * <ul>
 *   <li>The DigiLocker requester ({@link org.sangam.integration.connector.gov.DigiLockerRequesterConnector}) takes its
 *       base URL, its five paths ({@code Paths}; server keys {@code sangam.connectors.digilocker.requester.paths.*}),
 *       the client id and the client secret by {@code secret://} reference from configuration, so the same connector
 *       talks to the endpoint and paths given to the requester at onboarding, directly or through the gateway.</li>
 *   <li>Onboarding (requester registration, the client credentials, the redirect URI, the scope of documents) is done
 *       by the deploying organisation on the platform's partner portal; Sangam ships no credential and makes no call
 *       until a base URL and a secret reference are configured.</li>
 *   <li>The OpenAPI document a gateway publishes for an API can be stored with the package as an API contract
 *       (M-068, {@code org.sangam.integration.connector.contract}) and bound to a task by {@code sangam:operationRef}.</li>
 *   <li>Publishing Sangam's own API through a gateway uses its OpenAPI 3 contract ({@code sangam-rest/api/sangam-api.yaml});
 *       the gateway's onboarding decides the published subset and its authentication.</li>
 * </ul>
 * The exact production paths and onboarding steps change with the platform's releases; take them from the partner
 * portal documentation at onboarding and set them in configuration, never in code.
 */
package org.sangam.integration.connector.gov;
