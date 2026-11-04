package org.sangam.spike.it.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Runs an experiment class only for the selected engines and databases.
 *
 * <ul>
 *   <li>{@code -Dspike.engines=operaton,flowable} (default both). The engine of a class is read from its name
 *       ({@code *FlowableTest}, {@code *OperatonTest}): the class-level condition runs before the instance exists, so
 *       {@link ExperimentBase#kind()} cannot be asked.</li>
 *   <li>{@link PostgresOnly}: the class is skipped unless {@code -Dspike.db=postgres} (T35 uses pg_dump/pg_restore).</li>
 * </ul>
 * A skipped class writes no result entry.
 */
public final class EngineSelection implements ExecutionCondition {

    /** The class needs PostgreSQL (for example pg_dump inside the container). */
    @Inherited
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface PostgresOnly {
    }

    public static Set<String> selectedEngines() {
        return Arrays.stream(System.getProperty("spike.engines", "operaton,flowable").split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Class<?> cls = context.getTestClass().orElse(null);
        if (cls == null || context.getTestMethod().isPresent()) {
            return ConditionEvaluationResult.enabled("method level: decided at class level");
        }
        String name = cls.getSimpleName().toLowerCase(Locale.ROOT);
        Set<String> engines = selectedEngines();
        for (EngineKind k : EngineKind.values()) {
            String e = k.name().toLowerCase(Locale.ROOT);
            if (name.contains(e) && !engines.contains(e)) {
                return ConditionEvaluationResult.disabled(cls.getSimpleName() + ": engine " + e + " not in -Dspike.engines=" + engines);
            }
        }
        if (cls.isAnnotationPresent(PostgresOnly.class) && Db.kind() != Db.Kind.POSTGRES) {
            return ConditionEvaluationResult.disabled(cls.getSimpleName() + " runs on PostgreSQL only (-Dspike.db=" + Db.kind().key() + ")");
        }
        return ConditionEvaluationResult.enabled("selected");
    }
}
