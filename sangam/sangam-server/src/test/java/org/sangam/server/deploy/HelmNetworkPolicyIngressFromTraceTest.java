// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SG-39 follow-up: {@code networkPolicy.ingressFrom.cidrs} lets an edge load balancer that reaches the pods directly
 * (an AWS ALB in IP target mode, an internal load balancer's subnet) through the NetworkPolicies, without a hand-made
 * policy. Text trace of the chart (helm is not in the unit-test build; the value rules are in
 * {@link HelmValuesSchemaTest}):
 *
 * <ol>
 *   <li>Every NetworkPolicy peer list that admits the ingress controller namespace also admits the CIDRs, as one
 *       {@code ipBlock} peer each, in the same {@code from} list (so on the same ports): the server API, the UI and the
 *       SCALE components (the gateway the Ingress routes to when {@code scale.gateway.enabled}).</li>
 *   <li>The peers are rendered by a {@code range}, so an empty list (the default) renders nothing.</li>
 * </ol>
 */
class HelmNetworkPolicyIngressFromTraceTest {

    private static final Path CHART = Path.of("..", "deploy", "helm", "sangam");

    private static String read(String p) throws IOException {
        return Files.readString(CHART.resolve(p)).replace("\r\n", "\n");
    }

    /** The ingress-controller peer immediately followed by the CIDR peers. */
    private static Pattern controllerThenCidrs(String namespaceExpr, String cidrsExpr) {
        return Pattern.compile("        - namespaceSelector:\\n            matchLabels:\\n"
                + "              kubernetes\\.io/metadata\\.name: \\{\\{ " + Pattern.quote(namespaceExpr) + " }}\\n"
                + "        \\{\\{- range " + Pattern.quote(cidrsExpr) + " }}\\n"
                + "        - ipBlock: \\{ cidr: \\{\\{ \\. }} }\\n"
                + "        \\{\\{- end }}\\n");
    }

    private static int count(Matcher m) {
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    @Test
    void everyIngressControllerPeerAlsoAdmitsTheCidrs() throws IOException {
        String np = read("templates/networkpolicy.yaml");
        assertEquals(2, count(Pattern.compile("kubernetes\\.io/metadata\\.name: \\{\\{ \\$np\\.ingressControllerNamespace }}").matcher(np)),
                "server and UI policies admit the ingress controller");
        assertEquals(2, count(controllerThenCidrs("$np.ingressControllerNamespace", "$np.ingressFrom.cidrs").matcher(np)),
                "both also admit networkPolicy.ingressFrom.cidrs");

        String scale = read("templates/scale.yaml");
        assertEquals(1, count(Pattern.compile("kubernetes\\.io/metadata\\.name: \\{\\{ \\$\\.Values\\.networkPolicy\\.ingressControllerNamespace }}")
                .matcher(scale)), "the SCALE component policy admits the ingress controller");
        assertEquals(1, count(controllerThenCidrs("$.Values.networkPolicy.ingressControllerNamespace",
                "$.Values.networkPolicy.ingressFrom.cidrs").matcher(scale)), "and networkPolicy.ingressFrom.cidrs");
    }

    @Test
    void theCidrsShareTheControllerRuleAndItsPorts() throws IOException {
        String np = read("templates/networkpolicy.yaml");
        // Server: the CIDR peers sit in the first from-list, whose ports are the API port only (never management).
        String server = np.substring(np.indexOf("name: {{ $fullname }}-server\n"), np.indexOf("    - from:\n        - namespaceSelector:\n"
                + "            matchLabels:\n              kubernetes.io/metadata.name: {{ $np.monitoringNamespace }}"));
        assertTrue(server.contains("{{- range $np.ingressFrom.cidrs }}"));
        assertTrue(server.endsWith("      ports:\n        - { protocol: TCP, port: {{ .Values.server.service.port }} }\n"),
                "server: the API port");
        String ui = np.substring(np.indexOf("name: {{ $fullname }}-ui\n"));
        assertTrue(Pattern.compile("\\{\\{- range \\$np\\.ingressFrom\\.cidrs }}\\n.*\\n\\s+\\{\\{- end }}\\n      ports:\\n"
                + "        - \\{ protocol: TCP, port: 8080 }").matcher(ui).find(), "UI: its http port");
    }

    @Test
    void valuesDefaultIsEmpty() throws IOException {
        assertTrue(Pattern.compile("(?m)^networkPolicy:\\n(?:  .*\\n|\\n)*?  ingressFrom:\\n    cidrs: \\[]$")
                .matcher(read("values.yaml")).find(), "networkPolicy.ingressFrom.cidrs: [] (renders no peer)");
    }
}
