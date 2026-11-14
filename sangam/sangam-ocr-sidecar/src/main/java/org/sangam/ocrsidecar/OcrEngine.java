// SPDX-License-Identifier: Apache-2.0
package org.sangam.ocrsidecar;

import java.util.List;

/** The engine behind the OCR sidecar; replaceable (the 0.1.0 engine is the Tesseract program). */
interface OcrEngine {

    /** Engine name and version for the answer, for example {@code tesseract 5.3.4}. */
    String name();

    /**
     * Reads the text of one checked image.
     *
     * @throws Unreadable when the engine cannot read these bytes as an image
     * @throws java.io.IOException when the engine itself cannot run
     */
    String recognise(byte[] image, List<String> languages) throws Unreadable, java.io.IOException,
            InterruptedException;

    /** The engine refused the input (for example a corrupt image). */
    final class Unreadable extends Exception {
        Unreadable(String message) {
            super(message);
        }
    }
}
