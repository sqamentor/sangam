# sangam-spring-boot-starter (M-014, embedded mode)

Sangam inside your own Spring Boot application, with one dependency. The starter does not contain a second copy of
Sangam's wiring: it imports the standalone server's own `*Wiring` classes, bridges and controllers from
`sangam-server`'s library jar (classifier `plain`). The server and an embedding application run the same wiring.

## Requirements

| Item | Value |
|---|---|
| Spring Boot | 4.1.x (checked at start-up; the Operaton 2.2 starter needs it) |
| JVM | default time zone UTC (`-Duser.timezone=UTC`; checked at start-up, ADR-D-27) |
| Jackson databind | 2.22.x (checked when the FEEL engine is installed) |
| Liquibase | 4.x (checked at start-up; 5.x is FSL-licensed, LIC-7) |
| BOM order | import `sangam-bom` **before** the Spring Boot BOM |
| JDBC driver | yours: the starter brings no driver (LIC-4/LIC-5) |

```xml
<dependencyManagement>
  <dependencies>
    <dependency><groupId>io.github.sqamentor</groupId><artifactId>sangam-bom</artifactId><version>0.1.0</version>
                <type>pom</type><scope>import</scope></dependency>
    <!-- then the Spring Boot BOM -->
  </dependencies>
</dependencyManagement>
<dependencies>
  <dependency><groupId>io.github.sqamentor</groupId><artifactId>sangam-spring-boot-starter</artifactId></dependency>
  <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
</dependencies>
```

## Modules

The starter reads the same `sangam.modules.<module>.enabled` flags as the server. In a host:

| On by default (core) | Off until you enable them |
|---|---|
| `engine`, `integration` (audit chain, outbox, inbox, connectors), `security`, `ports`, the REST API | `config`, `packs`, `onboarding`, `ledger`, `billing`, `registry`, `resources`, `case`, `programme`, `geo`, `field`, `connectors-health`, `channels`, `ai`, `ops`, `modeller`, `pipelines`, `telemetry`, `compliance` |

`compliance` is off because it needs a jurisdiction (`sangam.compliance.jurisdictions`), and Sangam has no default
jurisdiction (ADR-D-20, SL-104). If your application processes personal data under a law that has a jurisdiction
pack (for example DPDP), enable it together with its jurisdiction.

Enable a module with a property, for example `sangam.modules.billing.enabled=true` (billing also needs `config`,
`ledger` and `registry`).

## Defaults

Sangam's defaults (`META-INF/sangam/sangam-defaults.yaml`) are added as the lowest-precedence property source,
`sangamDefaults`, below Spring Boot's `defaultProperties` too: your `application.yaml`, profiles, default properties
(`SpringApplicationBuilder.properties`), system properties, environment and command line override every key
(decision 334). They are
the server's `application.yaml` without the server's own deployment settings (`server.*`, `management.*`,
`logging.*`, `spring.application`, `spring.profiles`, `spring.lifecycle`), plus the module defaults above.
`sangam.starter.defaults.enabled=false` switches them off.

## Database schema

Decision 334. In a validated development environment (`sangam.security.production=false` with a development profile
`dev`, `h2`, `test` or `local`, decision 143) the starter applies the product changelog at start and lets the engine
create its tables, so a host starts on an empty database. Everywhere else, a missing or contradictory production
setting included, it creates and changes no schema: `operaton.bpm.database.schema-update` defaults to `false` and
`sangam.liquibase.mode` to `validate`; a start on a database without the engine tables is refused with
`ENGINE_SCHEMA_MISSING` before anything is created, and one with pending product changesets is refused by `validate`.
Create or upgrade the schema with a one-shot run of your application with `SANGAM_LIQUIBASE_MODE=migrate-only` (it
applies the product changelog and the engine schema, then exits; deploy README section 1.3), or apply the SQL of
`SANGAM_LIQUIBASE_MODE=update-sql`. A value you set yourself wins, as for every default: an explicit
`schema-update: true` or `SANGAM_LIQUIBASE_MODE=startup` is honoured in production, as in the server.
## Your own routes and security

Sangam's `SecurityFilterChain` applies to every request and ends with `denyAll()`. To serve your own routes,
declare your own chain for them, with a request matcher and a higher precedence:

```java
@Bean
@Order(1)
SecurityFilterChain hostChain(HttpSecurity http) throws Exception {
    return http.securityMatcher("/shop/**")
            .authorizeHttpRequests(a -> a.anyRequest().authenticated())
            .build();
}
```

Requests to `/shop/**` then use your chain; everything else (Sangam's `/api/v1`, `/engine-rest`, `/cds-services`,
the actuator) stays on Sangam's chain.

## The standalone server

`sangam-server` is unchanged: its fat jar keeps its name (`sangam-server-<v>.jar`), it scans its own packages, and
the starter backs off when it finds the server's wiring already loaded. `ServerBeanSetEqualityTest` proves that the
server's bean set is the same with and without the starter, and that a host with the server's settings gets the
server's bean set.
