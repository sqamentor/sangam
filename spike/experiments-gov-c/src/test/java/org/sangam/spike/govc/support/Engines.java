package org.sangam.spike.govc.support;

/** Selects the engine of this JVM from {@code -Dgov.engine=flowable|operaton} (set per surefire execution). */
public final class Engines {

    private static Native nat;

    private Engines() {
    }

    public static String engine() {
        String e = System.getProperty("gov.engine");
        if (e == null || e.isBlank()) {
            throw new IllegalStateException("-Dgov.engine=flowable|operaton is not set (run through the pom executions)");
        }
        return e.trim().toLowerCase();
    }

    public static synchronized Native nat() {
        if (nat == null) {
            String cls = switch (engine()) {
                case "flowable" -> "org.sangam.spike.govc.hooks.flowable.FlowableNative";
                case "operaton" -> "org.sangam.spike.govc.hooks.operaton.OperatonNative";
                default -> throw new IllegalStateException("unknown engine " + engine());
            };
            try {
                nat = (Native) Class.forName(cls).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot load " + cls, e);
            }
        }
        return nat;
    }
}
