// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.spi;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.packs.spi.PackDescriptor.AcceptanceScenario;
import org.sangam.packs.spi.PackDescriptor.ConnectorUse;
import org.sangam.packs.spi.PackDescriptor.PackResource;
import org.sangam.packs.spi.PackDescriptor.SeedRole;
import org.sangam.packs.spi.PackDescriptor.SeedSeat;

/**
 * A pack described by a {@code pack.json} manifest on the class path. The manifest format is the
 * {@link PackDescriptor} in JSON; roles and seats are read from the pack's seed file when the manifest names one.
 */
public final class ManifestPack implements SangamPack {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PackDescriptor descriptor;
    private final ClassLoader loader;

    private ManifestPack(PackDescriptor descriptor, ClassLoader loader) {
        this.descriptor = descriptor;
        this.loader = loader;
    }

    @Override
    public PackDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public InputStream open(String path) {
        InputStream in = loader.getResourceAsStream(path);
        if (in == null) {
            throw new UncheckedIOException(new java.io.FileNotFoundException(
                    "pack " + descriptor.id() + ": resource not found: " + path));
        }
        return in;
    }

    public static ManifestPack load(ClassLoader loader, String manifestPath) {
        try (InputStream in = loader.getResourceAsStream(manifestPath)) {
            if (in == null) {
                throw new IllegalArgumentException("manifest not found: " + manifestPath);
            }
            JsonNode m = JSON.readTree(in);
            List<PackResource> res = new ArrayList<>();
            for (JsonNode r : m.path("resources")) {
                res.add(new PackResource(PackResource.Kind.valueOf(r.path("kind").asText()), r.path("path").asText()));
            }
            List<SeedRole> roles = new ArrayList<>();
            List<SeedSeat> seats = new ArrayList<>();
            String seed = m.path("seed").asText(null);
            if (seed != null) {
                try (InputStream s = loader.getResourceAsStream(seed)) {
                    if (s != null) {
                        JsonNode sd = JSON.readTree(s);
                        for (JsonNode r : sd.path("roles")) {
                            roles.add(new SeedRole(r.path("code").asText(), texts(r.path("name"))));
                        }
                        for (JsonNode st : sd.path("seats")) {
                            seats.add(new SeedSeat(st.path("id").asText(), st.path("role").asText(),
                                    st.path("office").asText(), st.path("level").asText(null),
                                    st.path("reportsTo").asText(null),
                                    texts(st.path("name"))));
                        }
                    }
                }
            }
            List<ConnectorUse> conns = new ArrayList<>();
            for (JsonNode c : m.path("connectors")) {
                conns.add(new ConnectorUse(c.path("id").asText(), c.path("capabilityClass").asText("c"),
                        c.path("govTag").asText(""), c.path("purpose").asText("")));
            }
            List<AcceptanceScenario> acc = new ArrayList<>();
            for (JsonNode a : m.path("acceptance")) {
                Map<String, Object> vars = new LinkedHashMap<>();
                a.path("startVariables").fields().forEachRemaining(e -> vars.put(e.getKey(), value(e.getValue())));
                acc.add(new AcceptanceScenario(a.path("id").asText(), a.path("title").asText(), strings(a.path("refs")),
                        a.path("given").asText(""), a.path("when").asText(""), a.path("then").asText(""), vars,
                        a.path("expectState").asText(null)));
            }
            PackDescriptor d = new PackDescriptor(m.path("id").asText(), m.path("version").asText(),
                    texts(m.path("name")), m.path("domain").asText(""), m.path("description").asText(""),
                    strings(m.path("mainProcesses")), res, roles, seats, conns, strings(m.path("covers")), acc,
                    strings(m.path("dependsOn")), overrides(m.path("tenantOverrides")), placeholders(m.path("placeholders")));
            return new ManifestPack(d, loader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<PackDescriptor.Placeholder> placeholders(JsonNode n) {
        List<PackDescriptor.Placeholder> out = new ArrayList<>();
        for (JsonNode p : n) {
            out.add(new PackDescriptor.Placeholder(p.path("decision").asText(), p.path("rule").asText(""),
                    p.path("reason").asText("")));
        }
        return out;
    }

    private static Map<String, List<String>> overrides(JsonNode n) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (JsonNode o : n) {
            m.computeIfAbsent(o.path("tenant").asText(), k -> new ArrayList<>()).add(o.path("path").asText());
        }
        return m;
    }

    private static Map<String, String> texts(JsonNode n) {
        Map<String, String> m = new LinkedHashMap<>();
        n.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asText()));
        return m;
    }

    private static List<String> strings(JsonNode n) {
        List<String> l = new ArrayList<>();
        n.forEach(x -> l.add(x.asText()));
        return l;
    }

    private static Object value(JsonNode n) {
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isIntegralNumber()) {
            return n.asLong();
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        if (n.isContainerNode()) {
            return n.toString();
        }
        return n.isNull() ? null : n.asText();
    }
}
