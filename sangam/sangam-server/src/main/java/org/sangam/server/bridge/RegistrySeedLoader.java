// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.registry.party.PartyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Loads the party identifier-type and match-configuration seeds that packs ship (registry README §6: the pack seed is
 * the default layer; administrator changes in {@code REG_PARTY_CONFIG} win). Seeds are read from every jar on the class
 * path at {@value #PACK_SEEDS} and {@value #MODULE_SEEDS}. A seed file is either a JSON array of identifier-type
 * definitions, or an object {@code {"tenant": "*", "identifierTypes": [...], "matchConfig": {...}}}
 * ({@code tenant} defaults to {@code *} = every tenant). Idempotent: defining the same seed again replaces it.
 */
public final class RegistrySeedLoader {

    public static final String PACK_SEEDS = "classpath*:packs/*/registry/*.json";
    public static final String MODULE_SEEDS = "classpath*:META-INF/sangam/registry/*.json";
    private static final Logger LOG = LoggerFactory.getLogger(RegistrySeedLoader.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Result(int files, int identifierTypes, int matchConfigs) {
    }

    private RegistrySeedLoader() {
    }

    @SuppressWarnings("unchecked")
    public static Result load(PartyService parties, ClassLoader classLoader) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(classLoader);
        List<Resource> resources = new ArrayList<>();
        for (String pattern : List.of(PACK_SEEDS, MODULE_SEEDS)) {
            try {
                resources.addAll(List.of(resolver.getResources(pattern)));
            } catch (IOException e) {
                LOG.warn("cannot scan {} for registry seeds: {}", pattern, e.toString());
            }
        }
        int files = 0;
        int types = 0;
        int matches = 0;
        for (Resource r : resources) {
            try (InputStream in = r.getInputStream()) {
                Object doc = JSON.readValue(in, Object.class);
                String tenant = "*";
                List<Map<String, Object>> defs = List.of();
                Map<String, Object> match = null;
                if (doc instanceof List<?> l) {
                    defs = (List<Map<String, Object>>) l;
                } else if (doc instanceof Map<?, ?> m) {
                    Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) m);
                    if (map.get("tenant") != null) {
                        tenant = String.valueOf(map.get("tenant"));
                    }
                    if (map.get("identifierTypes") instanceof List<?> l) {
                        defs = (List<Map<String, Object>>) l;
                    }
                    if (map.get("matchConfig") instanceof Map<?, ?> mc) {
                        match = JSON.convertValue(mc, new TypeReference<LinkedHashMap<String, Object>>() { });
                    }
                }
                if (!defs.isEmpty()) {
                    types += parties.defineIdentifierTypes(tenant, defs).size();
                }
                if (match != null) {
                    parties.defineMatchConfig(tenant, match);
                    matches++;
                }
                files++;
            } catch (IOException | RuntimeException e) {
                // A broken seed must not stop the server; administrators can still save types (REG_PARTY_CONFIG).
                LOG.error("registry seed {} rejected: {}", r.getDescription(), e.toString());
            }
        }
        LOG.info("Registry seeds: {} files, {} identifier types, {} match configurations", files, types, matches);
        return new Result(files, types, matches);
    }
}
