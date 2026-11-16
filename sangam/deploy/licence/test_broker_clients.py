# SPDX-License-Identifier: Apache-2.0
"""RM-53 acceptance (REMEDIATION-PLAN, X3 R-15): every shipped distribution carries one client per broker family.

The two broker adapter sets (sangam-flowable-ports events/channel and sangam-integration publish, inbox, broker and
telemetry) are written against the same client libraries, managed once in the root pom (version.kafka and the
pluginManagement entries). This test reads the committed decision register, which lists every shipped component
version per distribution (decision-register.json, generated from the E16 inventory), and fails when a distribution
ships two clients of one broker family: two versions of one client, or a second client library for the same broker.

Run: python -I -m unittest discover -s sangam/deploy/licence -p "test_*.py" (governance.yml)."""
import json
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))

# broker family -> the purl prefixes (without version) of every Java client of that broker the register could hold.
# A family's first prefix is the client Sangam uses; any other is a second client.
FAMILIES = {
    "kafka": ["pkg:maven/org.apache.kafka/kafka-clients", "pkg:maven/org.springframework.kafka/",
              "pkg:maven/io.vertx/vertx-kafka-client", "pkg:maven/io.smallrye.reactive/smallrye-reactive-messaging-kafka"],
    "jms": ["pkg:maven/jakarta.jms/jakarta.jms-api", "pkg:maven/javax.jms/", "pkg:maven/org.apache.geronimo.specs/geronimo-jms"],
    "mqtt": ["pkg:maven/org.eclipse.paho/org.eclipse.paho.client.mqttv3", "pkg:maven/org.eclipse.paho/org.eclipse.paho.mqttv5.client",
             "pkg:maven/com.hivemq/hivemq-mqtt-client", "pkg:maven/org.fusesource.mqtt-client/", "pkg:maven/io.vertx/vertx-mqtt"],
    "nats": ["pkg:maven/io.nats/jnats", "pkg:maven/io.nats/java-nats-streaming"],
    "rabbitmq": ["pkg:maven/com.rabbitmq/amqp-client", "pkg:maven/com.rabbitmq/stream-client",
                 "pkg:maven/org.springframework.amqp/"],
}


def clients_per_distribution(register):
    """{distribution: {family: sorted [purl, ...]}} for every broker client the register lists."""
    out = {}
    for purl, entry in register.get("components", {}).items():
        base = purl.rsplit("@", 1)[0]
        for family, prefixes in FAMILIES.items():
            if any(base == p or (p.endswith("/") and base.startswith(p)) for p in prefixes):
                for dist in entry.get("distributions", []):
                    out.setdefault(dist, {}).setdefault(family, []).append(purl)
    return {d: {f: sorted(v) for f, v in fam.items()} for d, fam in out.items()}


def violations(register):
    return sorted(f"{dist}: {family} has {len(purls)} clients {purls}"
                  for dist, fams in clients_per_distribution(register).items()
                  for family, purls in fams.items() if len(purls) > 1)


class OneClientPerBroker(unittest.TestCase):
    def test_the_register_ships_one_client_per_broker_family(self):
        with open(os.path.join(HERE, "decision-register.json"), encoding="utf-8") as f:
            register = json.load(f)
        self.assertEqual([], violations(register))
        server = clients_per_distribution(register).get("server", {})
        # the server ships the Kafka, JMS and MQTT clients (NATS is the opt-in add-on, decision 232)
        self.assertEqual({"kafka", "jms", "mqtt"}, set(server), server)

    def test_a_second_version_or_a_second_client_fails(self):
        def reg(*purls):
            return {"components": {p: {"distributions": ["server"]} for p in purls}}
        self.assertEqual([], violations(reg("pkg:maven/org.apache.kafka/kafka-clients@3.9.2",
                                            "pkg:maven/org.eclipse.paho/org.eclipse.paho.client.mqttv3@1.2.5")))
        two_versions = violations(reg("pkg:maven/org.apache.kafka/kafka-clients@3.9.2",
                                      "pkg:maven/org.apache.kafka/kafka-clients@4.1.0"))
        self.assertEqual(1, len(two_versions), two_versions)
        two_clients = violations(reg("pkg:maven/org.eclipse.paho/org.eclipse.paho.client.mqttv3@1.2.5",
                                     "pkg:maven/com.hivemq/hivemq-mqtt-client@1.3.7"))
        self.assertTrue(two_clients and "mqtt has 2 clients" in two_clients[0], two_clients)
        # different distributions may each carry their own client
        split = {"components": {"pkg:maven/io.nats/jnats@2.26.4": {"distributions": ["nats"]},
                                "pkg:maven/io.nats/jnats@2.21.0": {"distributions": ["simulators"]}}}
        self.assertEqual([], violations(split))


if __name__ == "__main__":
    unittest.main()
