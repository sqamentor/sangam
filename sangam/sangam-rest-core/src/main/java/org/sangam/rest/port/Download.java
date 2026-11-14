// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * A file returned by an export or download endpoint (audit export, XES, OCEL, document content, evidence pack).
 * The content is <b>streamed</b>: {@link #body()} opens a fresh stream each time it is called and the REST layer copies
 * it to the response ({@code StreamingResponseBody}) without holding the whole file in memory.
 *
 * @param size the content length in bytes, or {@code -1} when unknown (chunked transfer)
 */
public record Download(String contentType, String filename, long size, Body body) {

    /** Opens the content; called once per response. The caller closes the stream. */
    @FunctionalInterface
    public interface Body {
        InputStream open() throws IOException;
    }

    /** In-memory content (small generated files). */
    public Download(String contentType, String filename, byte[] content) {
        this(contentType, filename, content.length, () -> new ByteArrayInputStream(content));
    }

    /** A streamed download of unknown or known length. */
    public static Download stream(String contentType, String filename, long size, Body body) {
        return new Download(contentType, filename, size, body);
    }

    /**
     * Reads the whole content. For callers that must post-process the bytes (bundling into a ZIP, hashing small
     * files); endpoints stream {@link #body()} instead.
     */
    public byte[] content() {
        try (InputStream in = body.open()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read download " + filename, e);
        }
    }
}
