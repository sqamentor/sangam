<!-- SPDX-License-Identifier: Apache-2.0 -->
# Keycloak realms (development) and identity-provider brokering

`generate_realm.py` writes `realm/sangam-realm.json` (tenant realm `sangam`), `realm/sangam-platform-realm.json`
(platform realm, SL-70) and the Grafana overview dashboard. The JSON files are generated: edit the script, then run it
twice and check that the second run changes nothing.

```
python -I deploy/compose/keycloak/generate_realm.py
```

All passwords and client secrets in these realms are development-only test values.

## A government or enterprise SAML identity provider: broker it in Keycloak (the default)

Owner decision 52 (M-076): a SAML 2.0 identity provider is connected by **Keycloak brokering**. Keycloak receives
and validates the SAML assertion, then issues the OIDC token that `sangam-server` already verifies. No SAML code runs
in Sangam, and the API keeps a single token path (bearer JWT, per-token identity cache). This path also works
air-gapped, because the IdP metadata is imported from a file, not fetched.

The tenant realm contains the broker `gov-sso-saml`. It is **disabled** and its endpoints are placeholders
(`https://idp.invalid/...`). It is configured as follows:

| Setting | Value | Why |
|---|---|---|
| NameID format | persistent | a stable subject; a transient id cannot link a person |
| Assertions | signed and encrypted required, request signed (RSA-SHA256), clock skew 60 s | |
| Sync mode | FORCE | name and e-mail follow the IdP on every sign-in |
| Trust e-mail | on | a brokered e-mail arrives verified (`email_verified=true`), as the claims profile requires |
| Mappers | e-mail, first name, last name from the standard OIDs (`urn:oid:0.9.2342.19200300.100.1.3`, `2.5.4.42`, `2.5.4.4`) | person data only |
| Tenant | hard-coded per broker (`tenant` user attribute) | an IdP never chooses the tenant |
| Roles and groups | **none** | AJ-05 step 3: seats and authority come from Sangam postings, not IdP groups |

To connect a real IdP:

1. In the realm's admin console, open *Identity providers*, then `gov-sso-saml`, then *Import from file*, and load
   the IdP's metadata XML. This sets the entity id, the SSO and SLO URLs and the signing certificate. Alternatively,
   enter them by hand.
2. Check the attribute names the IdP sends against the mappers. If its names differ from the standard OIDs, change the
   `attribute.name` of each mapper.
3. Set the tenant mapper's value to the department's tenant id. For several tenants, create one broker per tenant
   (alias `gov-sso-saml-<tenant>`).
4. Give the IdP the SP metadata of the realm: `<keycloak>/realms/sangam/broker/gov-sso-saml/endpoint/descriptor`.
5. Enable the broker.

The server then links each sign-in to a person through the **claims profile** (M-077,
`sangam.security.claims-profile.*`, class `org.sangam.security.oidc.ClaimsProfile`). For a government single
sign-on (for example Parichay), use the preset `verified-email` and set the domain in the deployment configuration:

```
sangam.security.claims-profile.preset=verified-email
sangam.security.claims-profile.name=gov-sso
sangam.security.claims-profile.email-domains=gov.in
```

With this configuration:

- the link is the verified e-mail (lower-cased) in the configured domain or a sub-domain of it, held in
  `SEC_PERSON.external_subject`;
- roles and groups from the token are not used;
- multi-factor is required.

A deployment that receives a stable IdP person id instead sets `link-by=CLAIM` and `link-claim=<attribute>`. The
attribute names of the national single sign-on are not public (PK-04), so they are configuration, not code. The
product carries no country or IdP default (SL-104).

The server's bearer-token path (`JwtPrincipalConverter`) applies the profile when any `claims-profile` key is set:
the link key becomes the principal, a refused link or a missing second factor is `401 invalid_token` with the
profile's code (`EMAIL_NOT_VERIFIED`, `EMAIL_DOMAIN_REFUSED`, `LINK_CLAIM_MISSING`, `MFA_REQUIRED`, ...), and the
`acr` values that count as multi-factor are `sangam.security.mfa-acr-values`. Without a profile the token path is
unchanged (principal claim `preferred_username`, roles as configured).

**Multi-factor with brokering.** The preset requires the token's `acr` or `amr` to show a second factor. Keycloak
reports its own login's authentication level, not the upstream IdP's. There are two ways to satisfy the check:

- a Keycloak post-broker login flow that adds a one-time password step, with an `amr` claim that shows it (the
  default, owner decision 113, set up by `configure-broker-otp.sh` below); or
- if the IdP enforces multi-factor itself, a profile override `requireMfa=false`, recorded as the deployment's
  decision.

### `configure-broker-otp.sh`: one-time password after a brokered sign-in (owner decision 113)

The realm import stays as generated. This script runs **after** the import with `kcadm.sh` (in the Keycloak image),
on every start. Each step checks the current state first and changes only what differs, so a second run changes
nothing:

1. **Flow** `sangam-broker-otp`: a top-level flow with a single execution, *OTP Form* (`auth-otp-form`), set to
   REQUIRED. Its authenticator config sets the authentication reference (`default.reference.value`) to `otp`, with a
   max age of `OTP_AMR_MAX_AGE` seconds (default 36000, the SSO session maximum). The flow has no username or
   password step, because the broker has already identified the user. It is not a copy of the browser flow, since
   the browser flow's login form would ask a brokered user for a password they do not have.
2. **Broker**: each alias in `BROKER_ALIASES` (default `gov-sso-saml`) gets this flow as its *post-broker login
   flow*, so the step runs after every brokered sign-in. The first-broker-login flow and the broker's other settings
   stay as imported.
3. **Enrolment**: the `CONFIGURE_TOTP` required action is registered and enabled. A user without an OTP credential
   is sent to it by the OTP form, so they enrol at their first sign-in.
4. **`amr` claim**: a protocol mapper `amr` (`oidc-amr-mapper`) on each client in `AMR_CLIENTS` (default
   `sangam-ui`, the client that receives browser sign-ins), in the ID token, the access token and introspection.
   The token then carries `amr: ["otp"]`, which the claims profile's `requireMfa` accepts (`mfa`, `otp`, `hwk`,
   `sms` and `swk` count as a second factor). The script stops with an error if the server has no AMR mapper type.

The admin credentials are read only from the environment or a secret file, and nothing secret is printed:

| Variable | Default | |
|---|---|---|
| `KEYCLOAK_URL` | `http://keycloak:8180` | |
| `KEYCLOAK_ADMIN_REALM` | `master` | |
| `KEYCLOAK_ADMIN` | (required) | admin user |
| `KEYCLOAK_ADMIN_PASSWORD_FILE` | | file holding the admin password (preferred) |
| `KEYCLOAK_ADMIN_PASSWORD` | | used when no password file is set |
| `SANGAM_REALM` | `sangam` | |
| `BROKER_ALIASES` | `gov-sso-saml` | space separated, for example `gov-sso-saml gov-sso-saml-t1` |
| `AMR_CLIENTS` | `sangam-ui` | space separated client ids |
| `FLOW_ALIAS` | `sangam-broker-otp` | |
| `OTP_AMR_MAX_AGE` | `36000` | seconds |
| `WAIT_SECONDS` | `300` | how long to wait for Keycloak and the imported realm |

kcadm keeps its access token in a private temporary directory that is removed on exit. kcadm takes the password as a
command-line argument, so the script must run in a container of its own (a one-shot init service on the Keycloak
image), never on a shared host. Run it like this:

```
sh /opt/sangam/keycloak/configure-broker-otp.sh
```

Wiring into compose start-up is a separate step (W3-M). The end-to-end check is the claim itself: after a brokered
sign-in with OTP, the `sangam-ui` token must carry `amr` with `otp` and pass `requireMfa`.

## No SAML in the server in 0.1.0 (owner decision 99)

In 0.1.0 a SAML identity provider is connected **only** through Keycloak brokering, as described above. The server has
no SAML service-provider session: every API call carries an OIDC bearer token, and a server-side SAML login would give
the API nothing to verify. `sangam.security.saml.enabled` stays off. If it is set to `true`, the server refuses to
start (`SamlWiring`) and names this README's brokering path. The plain SAML settings and the assertion mapping in
sangam-security (`org.sangam.security.saml`) are kept for a later release; nothing calls them in 0.1.0.
