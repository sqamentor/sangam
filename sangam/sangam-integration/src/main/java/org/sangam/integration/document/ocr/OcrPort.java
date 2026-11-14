// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.ocr;

import java.util.List;

/**
 * Optical character recognition of one image (M-103, owner decision 167). Implementations fail closed: when the text
 * cannot be obtained they throw {@link OcrUnavailable} (no engine, not ready, timed out) or {@link OcrRejected} (the
 * input itself was refused) and never return empty or partial text as if it were a result. The port only reads
 * pixels: the caller checks user permissions and tenant ownership of the document before calling it.
 */
public interface OcrPort {

    /** Media types every implementation accepts. */
    List<String> MEDIA_TYPES = List.of("image/png", "image/jpeg", "image/tiff");

    /**
     * @param image     the image bytes
     * @param mediaType one of {@link #MEDIA_TYPES}
     * @param languages engine language codes in priority order, for example {@code eng}, {@code hin}; at least one
     */
    OcrResult recognise(byte[] image, String mediaType, List<String> languages);

    /** Why no call can succeed now, or {@code null} when ready. Never contains a secret. */
    String notReadyReason();

    /** An OCR port with nothing configured: never ready, every call {@link OcrUnavailable}. */
    static OcrPort none(String reason) {
        return new OcrPort() {
            @Override
            public OcrResult recognise(byte[] image, String mediaType, List<String> languages) {
                throw new OcrUnavailable("OCR is not available: " + reason);
            }

            @Override
            public String notReadyReason() {
                return reason;
            }
        };
    }
}
