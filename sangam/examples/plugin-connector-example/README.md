<!-- SPDX-License-Identifier: Apache-2.0 -->
# Writing a Sangam plugin

A plugin is one signed jar dropped into `lib-ext/plugins`. It needs no change to the platform and is picked up at the next restart. This example adds a connector (`example.echo-ledger`) and a pack (`example.echo`) that uses it.

## What a plugin can provide

| Provides | How |
|---|---|
| Connector | `META-INF/services/org.sangam.core.integration.SangamConnector` |
| Pack | `META-INF/sangam/packs.idx` manifests, or `META-INF/services/org.sangam.packs.spi.SangamPack` |
| Pack requirement rules | `META-INF/sangam/pack-requires.json`, or `requires` / `conflicts` / `upgrades` in `pack.json` |
| Compliance jurisdiction | `META-INF/sangam/jurisdictions.idx` |
| Profile bundle | `META-INF/sangam/profiles.idx`, or `META-INF/services/org.sangam.core.plugin.ProfileBundleProvider` |
| FEEL functions | `META-INF/services/org.sangam.core.plugin.DmnFunctionProvider` |
| BPMN delegates | `META-INF/services/org.sangam.core.plugin.BpmnDelegateProvider` |

| UI | `ui/manifest.json` (`id`, `version`, `entry`, `styles`, `contributes`, `requires`) plus assets under `ui/`, in the jar or in a UI-only signed `.zip` |

A UI bundle follows the same signature, compatibility, enable/disable and audit rules. Allowed assets: js, mjs, css, json, map, svg, png, jpg, webp, woff, woff2 (no HTML), at most 64 MiB. In a jar, the UI manifest repeats the plugin's id and version. The shell reads `GET /api/v1/plugins/ui` with its bearer token and imports `GET /api/v1/plugins/ui/{id}/assets/{path}` directly, same-origin. **Assets are served without a token, so plugin UI code must not contain secrets**, nor any data that only signed-in users may see. A disabled or refused plugin's assets return 404. Signing proves who published the code, but a UI plugin still runs with the signed-in user's rights, so trust a vendor's key accordingly.

Every jar needs `META-INF/sangam/plugin.json` with `id`, `version` and `requires` (`sangam`, `engine`, `modules`: version ranges such as `>=2.2.0 <3.0.0`). A plugin that does not fit the platform is refused and reported, and the server still starts.

A service that implements `org.sangam.core.plugin.PluginAware` receives its settings (`sangam.plugins.config.<pluginId>.<key>`) and a private data folder.

**Class loading.** Each plugin has its own class loader. `org.sangam.*`, `org.operaton.*`, `org.slf4j.*`, Jackson, Spring and SnakeYAML come from the platform: declare them `provided`. Anything else must be inside the jar as flattened classes (nested jars are not read), and it cannot clash with the platform or other plugins.

## Build and sign

From `sangam/`, install the API once, then build the plugin:

```
mvnw.cmd -q -B -DskipTests -pl sangam-core -am install
mvnw.cmd -q -B -f examples/plugin-connector-example/pom.xml package
```

Sign with Ed25519 (RSA and EC keys also work). The signature is the base64 of a detached signature over the jar's bytes, in `<jar>.sig`:

```
openssl genpkey -algorithm ed25519 -out vendor.key
openssl pkey -in vendor.key -pubout -out vendor.pub
openssl pkeyutl -sign -inkey vendor.key -rawin -in plugin-connector-example-1.0.0.jar -out sig.bin
openssl base64 -A -in sig.bin -out plugin-connector-example-1.0.0.jar.sig
```

## Install

The operator decides whom to trust; a vendor cannot add itself.

```
lib-ext/plugins/plugin-connector-example-1.0.0.jar
lib-ext/plugins/plugin-connector-example-1.0.0.jar.sig
lib-ext/plugins/trusted-keys/vendor.pub
```

Restart, then check `GET /api/v1/admin/plugins`. `POST /api/v1/admin/plugins/{id}/disable` and `/enable` take effect at the next restart; `GET /api/v1/admin/plugins/audit` is the hash-chained trail. Unsigned jars load only on the `h2` and `dev` profiles. A jar whose signature does not verify is refused everywhere.

## Use it

Enable the pack and bind its connector when onboarding a tenant:

```json
{"packs": {"ids": ["example.echo"]},
 "connectors": [{"packConnector": "example.post", "connectorId": "example.echo-ledger", "operation": "post",
                 "test": {"operation": "post", "input": {"amount": "1"}, "invoke": true}}]}
```
