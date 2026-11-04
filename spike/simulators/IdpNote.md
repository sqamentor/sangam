# IdP note: Keycloak as the Parichay / JanParichay stand-in

**Why Keycloak:**
- Parichay (G2G) and JanParichay / MeriPehchaan (G2C) offer OAuth 2.0 / OIDC and SAML 2.0 (SANGAM.md IX.7d, GOV-6; R50).
- Their onboarding metadata and credentials are ON-REQUEST (PK-04), so under ADR-D-13 the spike uses a local IdP that speaks both protocols.
- Keycloak (Apache-2.0) does both.
- The same adapter later points at `parichay.staging.nic.in` or the JanParichay proxy by **configuration only**: issuer, metadata URL, client id or entity id, and certificates.

## Run it in Testcontainers

`src/test/java/org/sangam/spike/sim/idp/KeycloakIdpTest.java` starts Keycloak and checks both metadata endpoints. It is opt-in, because the image is about 0.5 GB:

```
$env:SPIKE_IDP='true'; .\mvnw.cmd -q -pl simulators verify -Dtest=KeycloakIdpTest
```

Core of it:

```java
new GenericContainer<>(DockerImageName.parse("quay.io/keycloak/keycloak:26.3.3"))
    .withExposedPorts(8080)
    .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")      // Keycloak >= 26 bootstrap admin
    .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")      // synthetic dev-only value
    .withCommand("start-dev")
    .waitingFor(Wait.forHttp("/realms/master").forStatusCode(200))
    .withStartupTimeout(Duration.ofMinutes(4));
```

Endpoints, on the mapped port:

| Protocol | Endpoint |
|---|---|
| OIDC discovery | `/realms/<realm>/.well-known/openid-configuration` |
| SAML 2.0 IdP metadata | `/realms/<realm>/protocol/saml/descriptor` |
| Admin console | `/admin` |

## Setting up a Parichay-like realm (for the experiments)

1. **Import a realm** instead of clicking through the console. Mount a realm JSON and start with `--import-realm`:
   `.withCopyFileToContainer(MountableFile.forClasspathResource("parichay-realm.json"), "/opt/keycloak/data/import/parichay-realm.json")`
   `.withCommand("start-dev", "--import-realm")`
2. **The realm `parichay` holds:**
   - an OIDC client `sangam-oidc`: confidential, Authorization Code + PKCE, redirect `http://localhost:*/callback`;
   - a SAML client `sangam-saml`: entity id = SP entity id, signed assertions, POST binding;
   - synthetic users, e.g. `officer.dealing` and `officer.approver`, with attributes `designation`, `orgUnit`, `employeeId`. These are mapped into tokens and assertions by protocol mappers.
3. **MFA stand-in:** a required action for OTP (`CONFIGURE_TOTP`). This mimics the Parichay Authenticator step.
4. **Session rules to test (R50):**
   - token validation: signature, `iss`, `aud`, `exp`;
   - session idle timeout: realm `ssoSessionIdleTimeout`;
   - front-channel and back-channel logout (OIDC `end_session_endpoint`, SAML SLO). These map to JanParichay's "login/logout/timeout/token-validation/handshake" checklist.
5. **JanParichay proxy model:** put a small reverse proxy in front of Keycloak if an experiment needs the "proxy component" of IX.7d. It is not needed for the spike.

## Keycloak alternatives (also open source)

- **Spring Authorization Server** for OIDC only.
- **SimpleSAMLphp** for SAML only.

Keycloak is preferred because it covers both protocols in one container.

## Notes

- **Resource use:** about 1 GB RAM in dev mode. Run it alone or next to one database only, given the 32 GB host and the one-database-at-a-time rule.
- **Data:** use only synthetic users and secrets (ADR-D-13). Never reuse real Parichay credentials here.
- **Windows:** surefire needs the `jdk.net.unixdomain.tmpdir` argLine described in README.md.
