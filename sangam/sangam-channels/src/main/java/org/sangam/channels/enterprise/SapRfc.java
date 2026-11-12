// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.enterprise;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.SecretResolver;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SAP RFC connector (NG-15) over a <b>customer-supplied</b> SAP Java Connector (JCo 3). Sangam never ships, bundles or
 * downloads JCo (licence rule, the same pattern as the LIC-2 database drivers): the customer puts {@code sapjco3.jar} and
 * its native library where the server loads drivers ({@code /opt/sangam/drivers} on {@code loader.path}, and the native
 * library on {@code java.library.path}). {@link JcoRfcClient} loads it reflectively at run time; when it is absent every
 * call fails with {@code SAP_JCO_NOT_AVAILABLE} and a message saying what to install, before anything is sent.
 *
 * <p>Operation {@code call}. Input: {@code function} (the function module), optional {@code imports}, {@code changing}
 * (name to value, a structure as a map), {@code tables} (name to a list of row maps), {@code commit} (a BAPI: run in one
 * stateful context, then {@code BAPI_TRANSACTION_COMMIT} with {@code WAIT=X} only when its {@code RETURN} has no type E or A
 * message, else {@code BAPI_TRANSACTION_ROLLBACK}), and {@code readOnly} (a function without an effect, so a lost answer
 * may be repeated). Output: {@code exports}, {@code changing}, {@code tables}. Values cross as JCo returns them (packed
 * numbers as decimal strings, R33).
 *
 * <p>Capability class (c): synchronous RFC has no idempotency key and no lookup by our operationId, so a communication or
 * system failure after the call left becomes UNKNOWN_OUTCOME unless {@code readOnly} is set. The destination host must be
 * on the tenant's egress allow-list as {@code https://<host>} (the same host-level check the IMAP source uses; JCo opens its
 * own socket, so the DNS pinning of the HTTP transport does not apply to it).
 */
public final class SapRfc {

    /** A neutral call, so the connector logic is independent of JCo and testable with a fake client. */
    public record Call(String function, Map<String, Object> imports, Map<String, Object> changing, Map<String, List<Map<String, Object>>> tables, boolean commit) {
    }

    public record Result(Map<String, Object> exports, Map<String, Object> changing, Map<String, List<Map<String, Object>>> tables) {
    }

    /** Destination settings. {@code properties} are extra non-secret {@code jco.client.*} / {@code jco.destination.*} keys. */
    public record Destination(String name, String host, String systemNumber, String client, String user, String passwordRef, String language,
                              Map<String, String> properties) {
    }

    /** Why a call failed, mapped from JCo's error groups. */
    public enum Failure {
        NOT_AVAILABLE, LOGON, FUNCTION_NOT_FOUND, ABAP_EXCEPTION, COMMUNICATION, SYSTEM_FAILURE, OTHER
    }

    public static final class RfcException extends Exception {
        private final Failure failure;
        private final String key;

        public RfcException(Failure failure, String key, String message, Throwable cause) {
            super(message, cause);
            this.failure = failure;
            this.key = key;
        }

        public Failure failure() {
            return failure;
        }

        /** The ABAP exception key, or null. */
        public String key() {
            return key;
        }
    }

    /** The RFC runtime. {@code commitIf} decides, after the call in a stateful context, whether to commit. */
    public interface Client {
        Result call(Destination destination, String password, Call call, java.util.function.Predicate<Result> commitIf) throws RfcException;
    }

    private SapRfc() {
    }

    public static SangamConnector connector(String id, ConnectorProfile profile, Destination destination, Client client, EgressPolicy egress, SecretResolver secrets) {
        ConnectorDescriptor d = new ConnectorDescriptor(id, "sap-rfc", CapabilityClass.C_NEITHER, List.of("call"), profile);
        return new SangamConnector() {
            @Override
            public ConnectorDescriptor descriptor() {
                return d;
            }

            @Override
            @SuppressWarnings("unchecked")
            public ConnectorResult invoke(ConnectorRequest r) {
                boolean readOnly = Boolean.parseBoolean(r.str("readOnly", "false"));
                try {
                    String function = r.required("function");
                    if (!function.matches("[A-Z0-9_/]{1,30}")) {
                        return ConnectorResult.BusinessFailure.of("INVALID_REQUEST", "function must be an RFC function module name");
                    }
                    Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> e : r.map("tables").entrySet()) {
                        if (!(e.getValue() instanceof List<?> rows)) {
                            return ConnectorResult.BusinessFailure.of("INVALID_REQUEST", "table " + e.getKey() + " must be a list of rows");
                        }
                        List<Map<String, Object>> list = new ArrayList<>();
                        for (Object row : rows) {
                            if (!(row instanceof Map<?, ?>)) {
                                return ConnectorResult.BusinessFailure.of("INVALID_REQUEST", "each row of table " + e.getKey() + " must be an object");
                            }
                            list.add((Map<String, Object>) row);
                        }
                        tables.put(e.getKey(), list);
                    }
                    Call call = new Call(function, r.map("imports"), r.map("changing"), tables, Boolean.parseBoolean(r.str("commit", "false")));
                    egress.check(r.tenantId(), URI.create("https://" + destination.host() + "/"));
                    String password = secrets.require(destination.passwordRef());
                    Result res = client.call(destination, password, call, SapRfc::noErrorMessage);
                    if (call.commit() && !noErrorMessage(res)) {
                        return new ConnectorResult.BusinessFailure("SAP_BAPI_ERROR", firstError(res), output(res));
                    }
                    return ConnectorResult.Success.of(output(res), null);
                } catch (IllegalArgumentException e) {
                    return ConnectorResult.BusinessFailure.of("INVALID_REQUEST", e.getMessage());
                } catch (EgressPolicy.EgressDeniedException e) {
                    return ConnectorResult.TransientFailure.noEffect(e.getMessage());
                } catch (IllegalStateException e) {
                    return ConnectorResult.TransientFailure.noEffect("credential unavailable, call not made: " + e.getMessage());
                } catch (RfcException e) {
                    return switch (e.failure()) {
                        case NOT_AVAILABLE -> ConnectorResult.BusinessFailure.of("SAP_JCO_NOT_AVAILABLE", e.getMessage());
                        case LOGON -> ConnectorResult.BusinessFailure.of("SAP_LOGON_FAILED", e.getMessage());
                        case FUNCTION_NOT_FOUND -> ConnectorResult.BusinessFailure.of("SAP_FUNCTION_NOT_FOUND", e.getMessage());
                        case ABAP_EXCEPTION -> ConnectorResult.BusinessFailure.of("SAP_ABAP_" + String.valueOf(e.key()).toUpperCase(Locale.ROOT), e.getMessage());
                        case COMMUNICATION, SYSTEM_FAILURE, OTHER -> readOnly ? new ConnectorResult.TransientFailure(e.getMessage(), false, null)
                                : ConnectorResult.OutcomeUnknown.of(e.failure() + ": " + e.getMessage());
                    };
                }
            }
        };
    }

    /** True when the BAPI {@code RETURN} (a structure or a table) holds no message of type E (error) or A (abort). */
    static boolean noErrorMessage(Result r) {
        return firstError(r) == null;
    }

    static String firstError(Result r) {
        List<Map<String, Object>> rows = new ArrayList<>(r.tables().getOrDefault("RETURN", List.of()));
        if (r.exports().get("RETURN") instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> one = (Map<String, Object>) m;
            rows.add(one);
        }
        for (Map<String, Object> row : rows) {
            String type = String.valueOf(row.getOrDefault("TYPE", ""));
            if ("E".equals(type) || "A".equals(type)) {
                return type + " " + row.getOrDefault("ID", "") + "/" + row.getOrDefault("NUMBER", "") + " " + row.getOrDefault("MESSAGE", "");
            }
        }
        return null;
    }

    private static Map<String, Object> output(Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exports", r.exports());
        out.put("changing", r.changing());
        out.put("tables", r.tables());
        return out;
    }
}
