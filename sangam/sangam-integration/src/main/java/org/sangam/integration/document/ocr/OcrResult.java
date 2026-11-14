// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.ocr;

import java.util.List;

/**
 * The text an OCR engine read from one image (M-103). Recognised text is machine output with errors: callers that
 * store it mark it as OCR output and keep it reviewable; it is never treated as a verified transcription.
 *
 * @param text      the recognised text (may be empty when the image holds no text)
 * @param engine    engine name and version, for example {@code tesseract 5.3.4}
 * @param languages the language codes the engine used
 */
public record OcrResult(String text, String engine, List<String> languages) {
    public OcrResult {
        if (text == null || engine == null || engine.isBlank() || languages == null || languages.isEmpty()) {
            throw new IllegalArgumentException("text, engine and languages are required");
        }
        languages = List.copyOf(languages);
    }
}
