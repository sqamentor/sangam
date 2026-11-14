// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.ocr;

/**
 * No OCR result can be obtained now: no service configured, missing credentials, not ready, busy, timed out, refused
 * credentials, or an answer this client cannot use. Never a successful recognition.
 */
public class OcrUnavailable extends RuntimeException {
    public OcrUnavailable(String message) {
        super(message);
    }
}
