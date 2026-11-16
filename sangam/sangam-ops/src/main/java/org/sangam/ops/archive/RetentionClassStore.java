// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.archive;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.ports.DocumentStore;

/**
 * RM-52 (decision 386(1)): the archive store enforces its records-retention class. Every object is stored under the
 * class ({@code <tenant>/records/<class>/...}) together with a retention marker {@code <key>.retention.json} (class,
 * days, digest, stored-at, retain-until). Until its retain-until time an object cannot be deleted through this store,
 * and a marker never can; an object without a marker is never deleted either (its retention is unknown).
 *
 * <p>The one early removal is {@link #supersede}: an erasure (DPDP, R23) replaces an archive that holds erased data with
 * a scrubbed archive of the same tree under the same class, and only then removes the old one.
 *
 * <p>The marker is enforced by every Sangam path, which reaches the store only through this class. Immutability against
 * the storage operator (S3 Object Lock, a WORM volume) is the store's own setting and outside this class.
 */
public final class RetentionClassStore implements DocumentStore {

    /** The suffix of a retention marker. */
    public static final String MARKER = ".retention.json";

    /** A delete or a store that the retention class does not allow. */
    public static final class RetentionRefusedException extends IllegalStateException {
        private final String code;

        public RetentionRefusedException(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DocumentStore delegate;
    private final String retentionClass;
    private final int retentionDays;
    private final Clock clock;

    public RetentionClassStore(DocumentStore delegate, String retentionClass, int retentionDays, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (delegate instanceof RetentionClassStore) {
            throw new IllegalArgumentException("the archive store is already a retention-class store");
        }
        this.retentionClass = Objects.requireNonNull(retentionClass, "retentionClass");
        if (retentionDays <= 0) {
            throw new IllegalArgumentException("archive retention must be positive");
        }
        this.retentionDays = retentionDays;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String retentionClass() {
        return retentionClass;
    }

    public int retentionDays() {
        return retentionDays;
    }

    @Override
    public String type() {
        return delegate.type() + "+retention";
    }

    /** Stores the object under the class and writes its retention marker; refused outside the class. */
    @Override
    public StoredObject put(String key, InputStream content, String contentType) throws IOException {
        requireInClass(key);
        StoredObject o = delegate.put(key, content, contentType);
        Instant now = clock.instant();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("retentionClass", retentionClass);
        m.put("retentionDays", retentionDays);
        m.put("sha256", o.sha256Hex());
        m.put("storedAt", now.toString());
        m.put("retainUntil", now.plus(Duration.ofDays(retentionDays)).toString());
        try {
            delegate.put(key + MARKER, new ByteArrayInputStream(CanonicalJson.write(m).getBytes(StandardCharsets.UTF_8)),
                    "application/json");
        } catch (IOException | RuntimeException e) {
            // no marker, no object: an object of the class is never left without its retention
            try {
                delegate.delete(key);
            } catch (IOException | RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        return o;
    }

    @Override
    public Optional<InputStream> get(String key) throws IOException {
        return delegate.get(key);
    }

    @Override
    public boolean exists(String key) throws IOException {
        return delegate.exists(key);
    }

    /** Deletes an object whose retention has passed; refused before that, for a marker, and without a marker. */
    @Override
    public void delete(String key) throws IOException {
        requireInClass(key);
        Instant until = retainUntil(key).orElseThrow(() -> new RetentionRefusedException("RETENTION_UNKNOWN",
                key + " has no retention marker of class " + retentionClass + "; it is kept"));
        if (clock.instant().isBefore(until)) {
            throw new RetentionRefusedException("RETENTION_ACTIVE", key + " is retained under class " + retentionClass
                    + " until " + until);
        }
        delegate.delete(key);
        delegate.delete(key + MARKER);
    }

    /**
     * Removes {@code key} before its retention has passed because {@code replacement}, an object of the same class in
     * the same folder with its own marker, now holds its content less the erased data (an erasure, R23). Refused for
     * any other replacement. Nothing to remove when {@code key} is already gone.
     *
     * @return whether {@code key} was removed
     */
    public boolean supersede(String key, String replacement) throws IOException {
        requireInClass(key);
        requireInClass(replacement);
        if (key.equals(replacement) || !folder(key).equals(folder(replacement))) {
            throw new RetentionRefusedException("RETENTION_SUPERSEDE_INVALID", replacement
                    + " does not replace " + key + " (another object of the same folder is required)");
        }
        if (!delegate.exists(replacement) || retainUntil(replacement).isEmpty()) {
            throw new RetentionRefusedException("RETENTION_SUPERSEDE_INVALID", "the replacement " + replacement
                    + " is not stored under class " + retentionClass);
        }
        if (!delegate.exists(key)) {
            return false;
        }
        delegate.delete(key);
        if (delegate.exists(key + MARKER)) {
            delegate.delete(key + MARKER);
        }
        return true;
    }

    /** The retain-until time of a stored object, from its marker; empty when it has none. */
    public Optional<Instant> retainUntil(String key) throws IOException {
        Optional<InputStream> in = delegate.get(key + MARKER);
        if (in.isEmpty()) {
            return Optional.empty();
        }
        Map<?, ?> m;
        try (InputStream s = in.get()) {
            m = JSON.readValue(s, Map.class);
        }
        if (!key.equals(m.get("key")) || !retentionClass.equals(m.get("retentionClass")) || m.get("retainUntil") == null) {
            throw new RetentionRefusedException("RETENTION_MARKER_INVALID", "the retention marker of " + key
                    + " is not one of class " + retentionClass);
        }
        return Optional.of(Instant.parse(String.valueOf(m.get("retainUntil"))));
    }

    private void requireInClass(String key) {
        String[] parts = key == null ? new String[0] : key.split("/", -1);
        if (parts.length < 4 || !"records".equals(parts[1]) || !retentionClass.equals(parts[2])) {
            throw new RetentionRefusedException("RETENTION_CLASS_MISMATCH", key + " is not a key of retention class "
                    + retentionClass + " (<tenant>/records/" + retentionClass + "/...)");
        }
        if (key.endsWith(MARKER)) {
            throw new RetentionRefusedException("RETENTION_MARKER_PROTECTED", key + " is a retention marker");
        }
    }

    private static String folder(String key) {
        return key.substring(0, key.lastIndexOf('/'));
    }
}
