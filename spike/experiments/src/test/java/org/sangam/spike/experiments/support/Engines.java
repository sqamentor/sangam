package org.sangam.spike.experiments.support;

/** Selects the engine of this JVM from {@code -Dexp.engine=flowable|operaton} (set per surefire execution). */
public final class Engines {

    private static EngineHooks hooks;

    private Engines() {
    }

    public static String engine() {
        String e = System.getProperty("exp.engine");
        if (e == null || e.isBlank()) {
            throw new IllegalStateException("-Dexp.engine=flowable|operaton is not set (run through the pom executions)");
        }
        return e.trim().toLowerCase();
    }

    public static synchronized EngineHooks hooks() {
        if (hooks == null) {
            String cls = switch (engine()) {
                case "flowable" -> "org.sangam.spike.experiments.hooks.flowable.FlowableHooks";
                case "operaton" -> "org.sangam.spike.experiments.hooks.operaton.OperatonHooks";
                default -> throw new IllegalStateException("unknown engine " + engine());
            };
            try {
                hooks = (EngineHooks) Class.forName(cls).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot load " + cls, e);
            }
        }
        return hooks;
    }
}
