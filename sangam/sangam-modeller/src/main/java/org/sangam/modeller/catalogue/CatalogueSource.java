// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.catalogue;

import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.modeller.catalogue.ExtensionCatalogue.CatalogueException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Where the platform-wide catalogue index, its detached signature and the artefacts come from (owner decision 380):
 * a mirrored directory ({@link Offline}, the air-gapped form) or a hosted HTTPS URL ({@link Hosted}). An artefact is
 * named by a relative path inside the index's own location; anything that leaves it is refused.
 */
public sealed interface CatalogueSource permits CatalogueSource.Offline, CatalogueSource.Hosted {

    byte[] index();

    /** The base64 Ed25519 signature over the exact index bytes. */
    String signature();

    byte[] artifact(String path);

    /** The index file and its signature file; artefacts are files under the index's directory. */
    record Offline(Path indexFile, Path signatureFile) implements CatalogueSource {

        @Override
        public byte[] index() {
            return read(indexFile, "CATALOGUE_INDEX_UNREADABLE");
        }

        @Override
        public String signature() {
            return new String(read(signatureFile, "CATALOGUE_INDEX_UNREADABLE"), StandardCharsets.UTF_8).strip();
        }

        @Override
        public byte[] artifact(String path) {
            Path dir = indexFile.toAbsolutePath().normalize().getParent();
            Path file;
            try {
                file = dir.resolve(relative(path)).normalize();
            } catch (RuntimeException e) {
                throw new CatalogueException("CATALOGUE_ARTIFACT_PATH_INVALID", String.valueOf(path));
            }
            if (!file.startsWith(dir)) {
                throw new CatalogueException("CATALOGUE_ARTIFACT_PATH_INVALID", path);
            }
            return read(file, "CATALOGUE_ARTIFACT_MISSING");
        }

        private static byte[] read(Path p, String code) {
            try {
                return Files.readAllBytes(p);
            } catch (NoSuchFileException e) {
                throw new CatalogueException(code, p.getFileName().toString());
            } catch (IOException e) {
                throw new CatalogueException(code, e.getClass().getSimpleName());
            }
        }
    }

    /**
     * The index at an HTTPS URL, its signature at the same URL plus {@code .sig}, artefacts relative to the index. Every
     * read goes through {@link Fetch}; in production {@link #over} the shared {@link HttpTransport} with the catalogue's
     * egress allow-list, so without an allow-list entry nothing is fetched.
     */
    record Hosted(URI indexUrl, Fetch fetch) implements CatalogueSource {

        public Hosted {
            if (indexUrl == null || !"https".equalsIgnoreCase(indexUrl.getScheme()) || indexUrl.getHost() == null) {
                throw new CatalogueException("CATALOGUE_URL_NOT_HTTPS", "the hosted catalogue index must be an https:// URL");
            }
            if (indexUrl.getRawQuery() != null || indexUrl.getRawFragment() != null || indexUrl.getRawUserInfo() != null) {
                throw new CatalogueException("CATALOGUE_URL_INVALID", "the index URL carries no query, fragment or credentials");
            }
        }

        @Override
        public byte[] index() {
            return fetch.get(indexUrl);
        }

        @Override
        public String signature() {
            return new String(fetch.get(URI.create(indexUrl + ".sig")), StandardCharsets.UTF_8).strip();
        }

        @Override
        public byte[] artifact(String path) {
            URI target;
            try {
                target = indexUrl.resolve(new URI(null, null, relative(path), null)).normalize();
            } catch (Exception e) {
                throw new CatalogueException("CATALOGUE_ARTIFACT_PATH_INVALID", String.valueOf(path));
            }
            String base = indexUrl.getRawPath().substring(0, indexUrl.getRawPath().lastIndexOf('/') + 1);
            if (!indexUrl.getScheme().equalsIgnoreCase(target.getScheme()) || !indexUrl.getHost().equalsIgnoreCase(target.getHost())
                    || indexUrl.getPort() != target.getPort() || !target.getRawPath().startsWith(base)) {
                throw new CatalogueException("CATALOGUE_ARTIFACT_PATH_INVALID", path);
            }
            return fetch.get(target);
        }
    }

    /** One GET; a refusal or failure is a {@link CatalogueException}. */
    @FunctionalInterface
    interface Fetch {
        byte[] get(URI uri);
    }

    /** Egress tenant of the platform-wide catalogue: its allow-list entries are platform entries (tenant {@code *}). */
    String PLATFORM = "*";

    /**
     * GETs through the transport. An egress refusal is {@code CATALOGUE_EGRESS_DENIED}; any other failure or a non-2xx
     * answer is {@code CATALOGUE_UNREACHABLE}.
     */
    static Fetch over(HttpTransport transport) {
        return uri -> {
            HttpTransport.Outcome o = transport.execute(PLATFORM, HttpTransport.Call.get(uri, Map.of("Accept", "*/*")), 0);
            return switch (o) {
                case HttpTransport.Outcome.Response r when r.is2xx() -> r.body();
                case HttpTransport.Outcome.Response r -> throw new CatalogueException("CATALOGUE_UNREACHABLE", "HTTP " + r.status() + " from " + uri.getHost());
                case HttpTransport.Outcome.NoEffect ne when ne.reason().startsWith("EGRESS_DENIED") ->
                        throw new CatalogueException("CATALOGUE_EGRESS_DENIED", ne.reason());
                case HttpTransport.Outcome.NoEffect ne -> throw new CatalogueException("CATALOGUE_UNREACHABLE", ne.reason());
                case HttpTransport.Outcome.Unknown u -> throw new CatalogueException("CATALOGUE_UNREACHABLE", u.reason());
            };
        };
    }

    /** The catalogue's own allow-list (platform entries); none means nothing may be fetched (R56, air-gapped default). */
    static EgressPolicy egress(java.util.List<String> allowList) {
        if (allowList == null || allowList.isEmpty()) {
            return EgressPolicy.denyAll();
        }
        return EgressPolicy.of(allowList.stream().map(s -> EgressPolicy.Rule.parse(PLATFORM, s, false)).toList(), 3, 64L * 1024 * 1024);
    }

    private static String relative(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("\\") || path.contains(":") || path.contains("\\")) {
            throw new CatalogueException("CATALOGUE_ARTIFACT_PATH_INVALID", String.valueOf(path));
        }
        return path;
    }
}
