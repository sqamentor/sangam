// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.ocr;

/**
 * The OCR service refused the input itself: unsupported media type, a language it does not offer, an image that is
 * too large, or bytes it cannot read as an image. Retrying the same input does not help.
 */
public class OcrRejected extends RuntimeException {
    private final int status;

    public OcrRejected(String message, int status) {
        super(message);
        this.status = status;
    }

    /** The HTTP status the service answered (400, 413, 415 or 422), or 0 when the client refused before sending. */
    public int status() {
        return status;
    }
}
