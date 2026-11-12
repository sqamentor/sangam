# Sangam Java SDK: Spring Boot adapter (`org.sangam:sangam-sdk-spring-boot`)

M-027. Runs a Sangam external-task worker inside a Spring Boot application. The Java SDK itself has no Spring
dependency; this artifact adds one auto-configuration.

```yaml
sangam:
  worker:
    base-url: https://sangam.example.org/api/v1
    token: ${SANGAM_WORKER_TOKEN}        # or declare a TokenProvider bean
    worker-id: billing-worker-1          # optional
    max-concurrency: 8                   # optional, and max-tasks-per-fetch, async-response-timeout, shutdown-timeout
```

```java
@Bean
TopicSubscription payment(PaymentGateway gateway) {
    return TopicSubscription.topic("payment", ctx -> Map.of("paid", true)).build();
}

@Bean
Saga reserveStock(Inventory inventory) {
    return Saga.step("reserve")
            .action("stock.reserve", ctx -> Map.of("reserved", inventory.reserve(ctx.operationId())))
            .compensation("stock.release", c -> { inventory.release(c.idempotencyKey()); return Map.of(); })
            .build();
}
```

| Bean | Backs off when the application declares |
|---|---|
| `SangamWorkerProperties` (`sangam.worker.*`) | its own |
| `SangamClient` (base URL, `TokenProvider` bean or `token`) | its own |
| `SangamWorker` (every `TopicSubscription` bean, both topics of every `Saga` bean; a topic registered twice fails start-up) | its own |
| `SangamWorkerLifecycle` (`SmartLifecycle`, phase `Integer.MAX_VALUE - 1024`) | its own |

The worker starts after the context has started (web server, data sources) and stops first on shutdown: fetching
stops and running handlers get the worker's `shutdown-timeout` before the beans they use are destroyed.
`sangam.worker.enabled=false` turns the adapter off.

Build: a module of the root reactor (`<module>sdks/java-spring-boot</module>`, requested from the integrator). Spring
Boot comes from the BOM managed by `sangam-parent`; the application supplies Spring Boot at runtime.
