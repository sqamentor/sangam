# RM-54: MQTT client evaluation (Paho against a maintained client)

Recorded 2026-11-16 by lane R7-rm from the code, the licence records and the local Maven repository. Nothing was
downloaded (decision 96). REMEDIATION-PLAN RM-54 asks for the evaluation and a recorded decision; a client change is
the owner's decision.

## What Sangam uses today

| Item | Evidence |
|---|---|
| Client | `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`, one version for the whole reactor (root `pom.xml` dependency management; `sangam-flowable-ports`, `sangam-integration` and `sangam-server` declare it without a version) |
| Shipped in | the server fat jar only (`deploy/licence/decision-register.json`: ACCEPTED WITH CONDITIONS, distributions `server`, EPL-2.0) |
| Licence | EPL-2.0 or EDL-1.0 (BSD-3-Clause); Sangam ships it under EPL-2.0, so the EPL source-location obligation of RM-16 applies |
| Callers | two classes: `sangam-flowable-ports/.../events/channel/MqttAdapters.java` (M-064 event channels: synchronous QoS 1/2 publish, persistent session with manual acks, reconnect for redelivery) and `sangam-integration/.../telemetry/MqttTelemetryConsumer.java` (telemetry subscriber, QoS 1, `cleanSession=false`, automatic reconnect) |
| API surface used | `MqttClient`, `MqttConnectOptions`, `MqttCallback`, `MqttMessage`, `IMqttDeliveryToken`, `MemoryPersistence`, `MqttException`: MQTT 3.1.1 only; no MQTT 5 feature, no file persistence |
| Containment | both callers sit behind Sangam interfaces (`ChannelAdapters.Provider`, loaded by class name only when Paho is on the class path; the telemetry consumer behind its own class), so a client change touches these two files and the poms |
| Second client | none: RM-53's check (`deploy/licence/test_broker_clients.py`) finds one MQTT client in every distribution |
| Local Maven repository | only `org.eclipse.paho.client.mqttv3` 1.2.5 and its parent; no other MQTT client is cached |

## The risk

The Paho Java client line has had no release after 1.2.5 in any record this repository holds, and the X1 review
(section 4, DR-15) lists it as a maintenance risk: a defect or vulnerability found later may get no upstream fix. No
vulnerability against 1.2.5 is recorded in `deploy/licence/VULNERABILITY-TRIAGE.md` or the dependency-check
suppressions; the dependency-check run of the test phase is the place that would surface one.

## Options

| Option | What it means | Licence | Cost and risk |
|---|---|---|---|
| A. Keep Paho mqttv3 1.2.5 for 0.1.0 | No change; the two callers stay as they are | EPL-2.0 (conditions as today) | None now. The maintenance risk stays and is watched by the dependency-check gate; a CVE without an upstream fix would force option B under time pressure |
| B. Move to the HiveMQ MQTT Client | Rewrite the two callers against its blocking API; MQTT 3.1.1 and 5 | Apache-2.0 (removes one EPL condition) | Needs a download of the client and its transitive closure (Netty, RxJava and others), new register rows (E16 inventory), licence review and a test-phase broker run of both callers; not verifiable in this repository today |
| C. Move to Paho's MQTT 5 client | Same project, newer API | EPL-2.0 or EDL-1.0 | Same maintenance risk as A; only worth it if MQTT 5 features are wanted |

## Recommendation and decision

Recommended: **option A for 0.1.0**, with option B evaluated in the test phase once its download is approved, because
the use is small (two classes, MQTT 3.1.1, QoS 1 and 2), contained behind Sangam interfaces, and a change needs a
download, new register rows and a live broker run that cannot be done now.

Owner question (open): keep Paho mqttv3 1.2.5 for 0.1.0 and evaluate the HiveMQ MQTT Client (download, licence review,
broker run) in the test phase (option A, recommended), or replace Paho before 0.1.0 (option B)?
