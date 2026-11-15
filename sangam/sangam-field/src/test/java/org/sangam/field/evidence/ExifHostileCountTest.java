// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * An IFD entry carries a 32-bit value count from the file. The JPEG parser of {@link ExifReader} reads at most
 * {@link ExifReader#MAX_VALUES} values of an entry: an entry declaring more is skipped (no allocation, no loop over
 * the declared count), the entries around it are still read, and parsing ends.
 */
class ExifHostileCountTest {

    private static final int ASCII = 2;
    private static final int RATIONAL = 5;
    private static final int MAKE = 0x010F;
    private static final int MODEL = 0x0110;
    private static final int SOFTWARE = 0x0131;

    /** One 12-byte IFD entry with its value field given as four raw bytes (inline value or offset). */
    private record Entry(int tag, int type, long count, byte[] value) {
    }

    /** A JPEG whose APP1 Exif segment holds a little-endian TIFF with one IFD of {@code entries}, then {@code tail}. */
    private static byte[] jpeg(byte[] tail, Entry... entries) {
        int tiffLength = 8 + 2 + entries.length * 12 + 4 + tail.length;
        ByteBuffer t = ByteBuffer.allocate(tiffLength).order(ByteOrder.LITTLE_ENDIAN);
        t.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        t.putShort((short) entries.length);
        for (Entry e : entries) {
            t.putShort((short) e.tag()).putShort((short) e.type()).putInt((int) e.count()).put(e.value());
        }
        t.putInt(0).put(tail);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int segment = 2 + 6 + tiffLength;
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, (byte) (segment >> 8), (byte) segment});
        out.writeBytes("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(t.array());
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD9});
        return out.toByteArray();
    }

    private static byte[] le(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    private static byte[] inline(String fourBytes) {
        return fourBytes.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void anEntryWithCountFFFFFFFFIsSkippedAndParsingTerminates() {
        byte[] image = jpeg(new byte[0],
                new Entry(MAKE, ASCII, 0xFFFFFFFFL, le(8)),
                new Entry(0x829A, RATIONAL, 0xFFFFFFFFL, le(8)),
                new Entry(MODEL, ASCII, 4, inline("Cam\0")));
        ExifReader.ExifData d = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> ExifReader.read(image));
        assertTrue(d.present(), "the segment is still read");
        assertNull(d.make(), "the entry with the hostile count is skipped");
        assertEquals("Cam", d.model(), "the entry after it is read");
    }

    @Test
    void theBoundIsExactlyMaxValues() {
        byte[] text = new byte[ExifReader.MAX_VALUES + 1];
        java.util.Arrays.fill(text, (byte) 'x');
        int tailAt = 8 + 2 + 3 * 12 + 4;
        byte[] image = jpeg(text,
                new Entry(MAKE, ASCII, ExifReader.MAX_VALUES, le(tailAt)),
                new Entry(SOFTWARE, ASCII, ExifReader.MAX_VALUES + 1L, le(tailAt)),
                new Entry(MODEL, ASCII, 4, inline("Cam\0")));
        ExifReader.ExifData d = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> ExifReader.read(image));
        assertEquals("x".repeat(ExifReader.MAX_VALUES), d.make(), "MAX_VALUES values are read");
        assertNull(d.software(), "one more is skipped");
        assertEquals("Cam", d.model());
    }

    @Test
    void aCountWhoseValuesLieOutsideTheSegmentIsSkipped() {
        byte[] image = jpeg(new byte[0],
                new Entry(MAKE, ASCII, 4000, le(8)),
                new Entry(MODEL, ASCII, 4, inline("Cam\0")));
        ExifReader.ExifData d = ExifReader.read(image);
        assertNull(d.make());
        assertEquals("Cam", d.model());
    }
}
