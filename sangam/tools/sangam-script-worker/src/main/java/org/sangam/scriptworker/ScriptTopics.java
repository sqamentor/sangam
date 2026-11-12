// SPDX-License-Identifier: Apache-2.0
package org.sangam.scriptworker;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The topic convention between the package importer and this worker (M-061). The engine runs no scripts
 * (ADR-D-8 script sandbox): the importer rewrites every BPMN {@code scriptTask} into an external {@code serviceTask}
 * on the language's topic, and this worker runs the script. Both sides follow this table, which the worker README
 * repeats for the importer:
 *
 * <table>
 *   <tr><th>Item</th><th>Value</th></tr>
 *   <tr><td>topic</td><td>{@code sangam.script.javascript} or {@code sangam.script.python}</td></tr>
 *   <tr><td>{@value #VAR_SOURCE}</td><td>input parameter: the script text, Base64 (RFC 4648, UTF-8 bytes). Base64
 *       because an input parameter's text may be read as an expression by the engine; Base64 has no {@code $},
 *       {@code #} or braces</td></tr>
 *   <tr><td>{@value #VAR_SHA256}</td><td>input parameter: lower-case hex SHA-256 of the UTF-8 script text (the
 *       package's frozen script hash, SANGAM.md package contents); the worker refuses a mismatch</td></tr>
 *   <tr><td>{@value #VAR_RESULT}</td><td>optional input parameter: the original {@code operaton:resultVariable};
 *       the script's value is completed under this name</td></tr>
 * </table>
 *
 * {@code scriptFormat} names map to a language by {@link #language(String)}; any other format (Groovy, JRuby ...)
 * is not rewritten and stays a deployment error (SCRIPT_IN_ENGINE).
 */
public final class ScriptTopics {

    public static final String TOPIC_PREFIX = "sangam.script.";
    public static final String VAR_SOURCE = "sangamScriptB64";
    public static final String VAR_SHA256 = "sangamScriptSha256";
    public static final String VAR_RESULT = "sangamScriptResultVariable";

    /** Variables of the convention itself; never passed to the script as inputs. */
    public static final Set<String> CONVENTION_VARIABLES = Set.of(VAR_SOURCE, VAR_SHA256, VAR_RESULT);

    /**
     * Engine-neutral format names only. Engine dialects ({@code nashorn}, {@code graal.js}, {@code jython}: Java
     * interop, Python 2) are not mapped: such a script would behave differently, so it stays a deployment error.
     */
    private static final Map<String, Language> FORMATS = Map.of(
            "javascript", Language.JAVASCRIPT, "js", Language.JAVASCRIPT, "ecmascript", Language.JAVASCRIPT,
            "python", Language.PYTHON, "python3", Language.PYTHON, "py", Language.PYTHON);

    private ScriptTopics() {
    }

    /** The external-task topic of a language. */
    public static String topic(Language language) {
        return TOPIC_PREFIX + language.id();
    }

    /** The language a topic serves, if it is one of this convention's topics. */
    public static Optional<Language> languageOfTopic(String topic) {
        if (topic == null || !topic.startsWith(TOPIC_PREFIX)) {
            return Optional.empty();
        }
        String id = topic.substring(TOPIC_PREFIX.length());
        for (Language l : Language.values()) {
            if (l.id().equals(id)) {
                return Optional.of(l);
            }
        }
        return Optional.empty();
    }

    /** The language of a BPMN {@code scriptFormat} (case-insensitive), or empty when the worker cannot run it. */
    public static Optional<Language> language(String scriptFormat) {
        return scriptFormat == null ? Optional.empty()
                : Optional.ofNullable(FORMATS.get(scriptFormat.strip().toLowerCase(Locale.ROOT)));
    }
}
