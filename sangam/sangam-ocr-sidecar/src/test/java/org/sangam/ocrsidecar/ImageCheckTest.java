// SPDX-License-Identifier: Apache-2.0
package org.sangam.ocrsidecar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Decision 153 limits for the OCR sidecar: the type comes from the bytes, the pixel count from the header. */
class ImageCheckTest {

    static byte[] png(int width, int height) {
        ByteBuffer b = ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN);
        b.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a});
        b.putInt(13).put("IHDR".getBytes(StandardCharsets.US_ASCII)).putInt(width).putInt(height);
        b.put(new byte[] {8, 0, 0, 0, 0}).putInt(0);
        return b.array();
    }

    static byte[] jpeg(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xff, (byte) 0xd8});
        out.writeBytes(new byte[] {(byte) 0xff, (byte) 0xe0, 0, 4, 0, 0}); // APP0, length 4
        out.writeBytes(new byte[] {(byte) 0xff, (byte) 0xc0, 0, 11, 8, (byte) (height >> 8), (byte) height,
                (byte) (width >> 8), (byte) width, 1, 1, 0x11, 0});
        out.writeBytes(new byte[] {(byte) 0xff, (byte) 0xd9});
        return out.toByteArray();
    }

    static byte[] tiff(boolean littleEndian, long width, long height) {
        ByteBuffer b = ByteBuffer.allocate(8 + 2 + 2 * 12 + 4)
                .order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        b.put(littleEndian ? new byte[] {'I', 'I'} : new byte[] {'M', 'M'}).putShort((short) 42).putInt(8);
        b.putShort((short) 2);
        b.putShort((short) 256).putShort((short) 4).putInt(1).putInt((int) width);
        b.putShort((short) 257).putShort((short) 3).putInt(1).putShort((short) height).putShort((short) 0);
        b.putInt(0);
        return b.array();
    }

    @Test
    void typesAreDetectedFromTheBytes() {
        assertEquals("image/png", ImageCheck.detect(png(10, 10)));
        assertEquals("image/jpeg", ImageCheck.detect(jpeg(10, 10)));
        assertEquals("image/tiff", ImageCheck.detect(tiff(true, 10, 10)));
        assertEquals("image/tiff", ImageCheck.detect(tiff(false, 10, 10)));
        assertNull(ImageCheck.detect("%PDF-1.7".getBytes(StandardCharsets.US_ASCII)));
        assertNull(ImageCheck.detect(new byte[0]));
    }

    @Test
    void sizesAreReadFromTheHeaders() {
        assertArrayEquals(new long[] {640, 480}, ImageCheck.png(png(640, 480)));
        assertArrayEquals(new long[] {2480, 3508}, ImageCheck.jpeg(jpeg(2480, 3508)));
        assertArrayEquals(new long[] {1700, 2200}, ImageCheck.tiff(tiff(true, 1700, 2200)));
        assertArrayEquals(new long[] {1700, 2200}, ImageCheck.tiff(tiff(false, 1700, 2200)));
    }

    @Test
    void theDeclaredTypeMustMatchAndThePixelCountMustFit() {
        assertDoesNotThrow(() -> ImageCheck.check(png(100, 100), "image/png", 10_000));
        assertDoesNotThrow(() -> ImageCheck.check(jpeg(100, 100), "image/jpeg; charset=x", 10_000));
        assertDoesNotThrow(() -> ImageCheck.check(tiff(true, 100, 100), "IMAGE/TIFF", 10_000));
        assertEquals(415, assertThrows(ImageCheck.Refused.class,
                () -> ImageCheck.check(png(10, 10), "image/jpeg", 10_000)).status());
        assertEquals(415, assertThrows(ImageCheck.Refused.class,
                () -> ImageCheck.check(png(10, 10), null, 10_000)).status());
        assertEquals(415, assertThrows(ImageCheck.Refused.class,
                () -> ImageCheck.check("%PDF-1.7".getBytes(StandardCharsets.US_ASCII), "image/png", 10_000)).status());
        assertEquals(413, assertThrows(ImageCheck.Refused.class,
                () -> ImageCheck.check(png(101, 100), "image/png", 10_000)).status());
        assertEquals(413, assertThrows(ImageCheck.Refused.class,
                () -> ImageCheck.check(png(Integer.MAX_VALUE, Integer.MAX_VALUE), "image/png", 10_000)).status());
        assertEquals(422, assertThrows(ImageCheck.Refused.class,
                () -> ImageCheck.check(png(0, 10), "image/png", 10_000)).status());
    }

    @Test
    void truncatedOrOddHeadersAreRefusedNotGuessed() {
        byte[] p = png(10, 10);
        byte[] shortPng = java.util.Arrays.copyOf(p, 20);
        assertNull(ImageCheck.png(shortPng));
        byte[] noFrame = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xda, 0, 2};
        assertNull(ImageCheck.jpeg(noFrame));
        byte[] badTiff = {'I', 'I', 42, 0, (byte) 0xff, (byte) 0xff, 0, 0};
        assertNull(ImageCheck.tiff(badTiff));
        assertEquals(422, assertThrows(ImageCheck.Refused.class,
                () -> ImageCheck.check(noFrame, "image/jpeg", 10_000)).status());
    }
}
