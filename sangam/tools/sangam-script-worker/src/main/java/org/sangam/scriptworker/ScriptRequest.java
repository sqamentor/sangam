// SPDX-License-Identifier: Apache-2.0
package org.sangam.scriptworker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One script execution, read from a locked work item (topic + fetched variables) by the {@link ScriptTopics}
 * convention, and checked before anything runs: a known language, a Base64 source within the size limit whose
 * SHA-256 equals the declared hash, and a valid result variable name.
 *
 * @param language       from the topic
 * @param source         the script text
 * @param resultVariable the original {@code resultVariable}, or null
 * @param inputs         every fetched variable except the convention's own, handed to the script
 */
public record ScriptRequest(Language language, String source, String resultVariable, Map<String, Object> inputs) {

    /** A work item that does not follow the convention; never retried (the model or the import is wrong). */
    public static final class InvalidScriptException extends Exception {
        public InvalidScriptException(String message) {
            super(message);
        }
    }

    public static ScriptRequest from(String topic, Map<String, Object> variables, ScriptLimits limits)
            throws InvalidScriptException {
        Language language = ScriptTopics.languageOfTopic(topic)
                .orElseThrow(() -> new InvalidScriptException("not a script topic: " + topic));
        Map<String, Object> vars = variables == null ? Map.of() : variables;
        if (!(vars.get(ScriptTopics.VAR_SOURCE) instanceof String b64) || b64.isBlank()) {
            throw new InvalidScriptException(ScriptTopics.VAR_SOURCE + " is missing: the script task was not imported"
                    + " by the script convention");
        }
        if (b64.length() > limits.sourceBytes() * 2L + 16) {   // cheap bound before decoding (Base64 + line breaks)
            throw new InvalidScriptException("script larger than " + limits.sourceBytes() + " bytes");
        }
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new InvalidScriptException(ScriptTopics.VAR_SOURCE + " is not Base64");
        }
        if (bytes.length > limits.sourceBytes()) {
            throw new InvalidScriptException("script larger than " + limits.sourceBytes() + " bytes");
        }
        if (!(vars.get(ScriptTopics.VAR_SHA256) instanceof String declared) || !sha256(bytes).equals(declared.strip()
                .toLowerCase(java.util.Locale.ROOT))) {
            throw new InvalidScriptException(ScriptTopics.VAR_SHA256 + " does not match the script (integrity check)");
        }
        Object rv = vars.get(ScriptTopics.VAR_RESULT);
        String resultVariable = null;
        if (rv != null) {
            if (!(rv instanceof String s) || !s.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
                throw new InvalidScriptException(ScriptTopics.VAR_RESULT + " is not a variable name");
            }
            resultVariable = s;
        }
        Map<String, Object> inputs = new LinkedHashMap<>(vars);
        inputs.keySet().removeAll(ScriptTopics.CONVENTION_VARIABLES);
        return new ScriptRequest(language, new String(bytes, StandardCharsets.UTF_8), resultVariable, inputs);
    }

    /** Lower-case hex SHA-256. */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }
}
