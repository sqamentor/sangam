// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.evidence;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M-132: the one {@link ExifReader} reads the same EXIF block from JPEG (APP1), PNG ({@code eXIf} chunk) and HEIC
 * ({@code Exif} item in an ISO base media file) and interprets it the same way. Fixtures are built byte by byte here
 * (big-endian TIFF), so no binary test resource is needed.
 */
class ExifReaderFormatsTest {

    // ---- fixtures ------------------------------------------------------------------------------

    /** One IFD entry; {@code pointsTo} >= 0 makes it a LONG offset to that IFD (Exif / GPS pointer). */
    record Entry(int tag, int type, int count, byte[] value, int pointsTo) {
        static Entry ascii(int tag, String s) {
            byte[] v = (s + "\0").getBytes(StandardCharsets.US_ASCII);
            return new Entry(tag, 2, v.length, v, -1);
        }

        static Entry rationals(int tag, long... numDen) {
            ByteBuffer b = ByteBuffer.allocate(numDen.length * 4);
            for (long x : numDen) {
                b.putInt((int) x);
            }
            return new Entry(tag, 5, numDen.length / 2, b.array(), -1);
        }

        static Entry pointer(int tag, int ifd) {
            return new Entry(tag, 4, 1, new byte[4], ifd);
        }
    }

    /** Big-endian TIFF with the given IFDs laid out one after another, values longer than 4 bytes after them. */
    static byte[] tiff(List<List<Entry>> ifds) {
        int[] at = new int[ifds.size()];
        int pos = 8;
        for (int i = 0; i < ifds.size(); i++) {
            at[i] = pos;
            pos += 2 + ifds.get(i).size() * 12 + 4;
        }
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int dataStart = pos;
        ByteBuffer out = ByteBuffer.allocate(dataStart + 4096);
        out.put(new byte[]{'M', 'M', 0, 42}).putInt(8);
        for (List<Entry> ifd : ifds) {
            out.putShort((short) ifd.size());
            for (Entry e : ifd) {
                out.putShort((short) e.tag()).putShort((short) e.type()).putInt(e.count());
                if (e.pointsTo() >= 0) {
                    out.putInt(at[e.pointsTo()]);
                } else if (e.value().length <= 4) {
                    out.put(Arrays.copyOf(e.value(), 4));
                } else {
                    out.putInt(dataStart + data.size());
                    data.writeBytes(e.value());
                }
            }
            out.putInt(0);
        }
        out.put(data.toByteArray());
        return Arrays.copyOf(out.array(), out.position());
    }

    /** Make, Model, DateTimeOriginal 2026-11-12 10:00 at +05:30, GPS 26°55'N 75°47'E at 04:30:00 UTC. */
    static byte[] sampleTiff() {
        List<Entry> ifd0 = List.of(Entry.ascii(0x010F, "FieldCam"), Entry.ascii(0x0110, "FC-1"),
                Entry.pointer(0x8769, 1), Entry.pointer(0x8825, 2));
        List<Entry> exif = List.of(Entry.ascii(0x9003, "2026:11:12 10:00:00"), Entry.ascii(0x9011, "+05:30"));
        List<Entry> gps = List.of(Entry.ascii(0x01, "N"), Entry.rationals(0x02, 26, 1, 55, 1, 0, 1),
                Entry.ascii(0x03, "E"), Entry.rationals(0x04, 75, 1, 47, 1, 0, 1),
                Entry.rationals(0x07, 4, 1, 30, 1, 0, 1), Entry.ascii(0x1D, "2026:11:12"));
        return tiff(List.of(ifd0, exif, gps));
    }

    static byte[] jpeg(byte[] tiff) {
        ByteBuffer b = ByteBuffer.allocate(tiff.length + 32);
        b.put((byte) 0xFF).put((byte) 0xD8);
        b.put((byte) 0xFF).put((byte) 0xE1).putShort((short) (2 + 6 + tiff.length));
        b.put("Exif\0\0".getBytes(StandardCharsets.US_ASCII)).put(tiff);
        b.put((byte) 0xFF).put((byte) 0xD9);
        return Arrays.copyOf(b.array(), b.position());
    }

    static byte[] pngChunk(String type, byte[] data) {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        return ByteBuffer.allocate(12 + data.length).putInt(data.length).put(t).put(data).putInt((int) crc.getValue()).array();
    }

    static byte[] png(byte[] tiff) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        o.writeBytes(pngChunk("IHDR", ByteBuffer.allocate(13).putInt(1).putInt(1).put((byte) 8).put((byte) 2).put((byte) 0)
                .put((byte) 0).put((byte) 0).array()));
        o.writeBytes(pngChunk("eXIf", tiff));
        o.writeBytes(pngChunk("IEND", new byte[0]));
        return o.toByteArray();
    }

    static byte[] box(String type, byte[]... payload) {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        for (byte[] x : payload) {
            p.writeBytes(x);
        }
        return ByteBuffer.allocate(8 + p.size()).putInt(8 + p.size()).put(type.getBytes(StandardCharsets.US_ASCII)).put(p.toByteArray()).array();
    }

    static byte[] fullBox(String type, int version, byte[]... payload) {
        byte[][] all = new byte[payload.length + 1][];
        all[0] = new byte[]{(byte) version, 0, 0, 0};
        System.arraycopy(payload, 0, all, 1, payload.length);
        return box(type, all);
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** ftyp (heic, mif1) + meta (hdlr pict, iinf with one Exif item, iloc v0) + mdat holding the Exif item. */
    static byte[] heic(byte[] tiff, String major, String... compatible) {
        byte[] item = ByteBuffer.allocate(4 + 6 + tiff.length).putInt(6).put(ascii("Exif\0\0")).put(tiff).array();
        ByteArrayOutputStream brands = new ByteArrayOutputStream();
        brands.writeBytes(ascii(major));
        brands.writeBytes(new byte[4]);
        for (String c : compatible) {
            brands.writeBytes(ascii(c));
        }
        byte[] ftyp = box("ftyp", brands.toByteArray());
        byte[] meta = meta(0, item.length);
        int offset = ftyp.length + meta.length + 8; // the item starts right after the mdat header
        meta = meta(offset, item.length);
        return ByteBuffer.allocate(ftyp.length + meta.length + 8 + item.length).put(ftyp).put(meta).put(box("mdat", item)).array();
    }

    static byte[] meta(int itemOffset, int itemLength) {
        byte[] hdlr = fullBox("hdlr", 0, new byte[4], ascii("pict"), new byte[12], new byte[]{0});
        byte[] infe = fullBox("infe", 2, ByteBuffer.allocate(4).putShort((short) 1).putShort((short) 0).array(), ascii("Exif"), new byte[]{0});
        byte[] iinf = fullBox("iinf", 0, ByteBuffer.allocate(2).putShort((short) 1).array(), infe);
        byte[] iloc = fullBox("iloc", 0, ByteBuffer.allocate(2 + 2 + 2 + 2 + 2 + 4 + 4)
                .put((byte) 0x44).put((byte) 0x00) // offset_size 4, length_size 4; base_offset_size 0
                .putShort((short) 1)                // item_count
                .putShort((short) 1)                // item_ID
                .putShort((short) 0)                // data_reference_index (this file)
                .putShort((short) 1)                // extent_count
                .putInt(itemOffset).putInt(itemLength).array());
        return fullBox("meta", 0, hdlr, iinf, iloc);
    }

    // ---- tests ---------------------------------------------------------------------------------

    static void assertSample(ExifReader.ExifData d, String format) {
        assertTrue(d.present(), format);
        assertEquals("FieldCam", d.make(), format);
        assertEquals("FC-1", d.model(), format);
        assertEquals(LocalDateTime.parse("2026-11-12T10:00:00"), d.localTime(), format);
        assertEquals(Instant.parse("2026-11-12T04:30:00Z"), d.originalTime(), format);
        assertEquals(26 + 55 / 60.0, d.gpsLat(), 1e-9, format);
        assertEquals(75 + 47 / 60.0, d.gpsLon(), 1e-9, format);
        assertEquals(Instant.parse("2026-11-12T04:30:00Z"), d.gpsTime(), format);
    }

    @Test
    void jpegExifIsRead() {
        assertSample(ExifReader.read(jpeg(sampleTiff())), "jpeg");
    }

    @Test
    void pngExifChunkIsRead() {
        byte[] png = png(sampleTiff());
        assertTrue(ExifReader.isPng(png));
        assertSample(ExifReader.read(png), "png");
    }

    @Test
    void heicExifItemIsRead() {
        byte[] heic = heic(sampleTiff(), "heic", "mif1", "heic");
        assertTrue(ExifReader.isHeif(heic));
        assertSample(ExifReader.read(heic), "heic");
    }

    @Test
    void allFormatsGiveTheSameResult() {
        byte[] t = sampleTiff();
        ExifReader.ExifData j = ExifReader.read(jpeg(t));
        assertEquals(j, ExifReader.read(png(t)));
        assertEquals(j, ExifReader.read(heic(t, "heic", "mif1", "heic")));
        assertEquals(j.toMap(), ExifReader.read(png(t)).toMap());
    }

    @Test
    void southAndWestAreNegative() {
        List<Entry> ifd0 = List.of(Entry.pointer(0x8825, 1));
        List<Entry> gps = List.of(Entry.ascii(0x01, "S"), Entry.rationals(0x02, 10, 1, 30, 1, 0, 1),
                Entry.ascii(0x03, "W"), Entry.rationals(0x04, 20, 1, 15, 1, 0, 1));
        ExifReader.ExifData d = ExifReader.read(png(tiff(List.of(ifd0, gps))));
        assertEquals(-10.5, d.gpsLat(), 1e-9);
        assertEquals(-20.25, d.gpsLon(), 1e-9);
    }

    @Test
    void heifIsRecognisedByACompatibleBrand() {
        assertTrue(ExifReader.isHeif(heic(sampleTiff(), "xxxx", "mif1")));
        assertFalse(ExifReader.isHeif(heic(sampleTiff(), "isom", "mp41")), "an MP4 video is not HEIF");
    }

    @Test
    void imagesWithoutExifOrUnknownFormatsAreNone() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        o.writeBytes(pngChunk("IHDR", new byte[13]));
        o.writeBytes(pngChunk("IEND", new byte[0]));
        assertEquals(ExifReader.ExifData.NONE, ExifReader.read(o.toByteArray()), "PNG without eXIf");
        assertEquals(ExifReader.ExifData.NONE, ExifReader.read(ascii("GIF89a\1\0\1\0\0\0\0")), "GIF is not read");
        assertEquals(ExifReader.ExifData.NONE, ExifReader.read(null));
        assertEquals(ExifReader.ExifData.NONE, ExifReader.read(new byte[3]));
    }

    @Test
    void malformedContainersNeverThrow() {
        byte[] png = png(sampleTiff());
        byte[] heic = heic(sampleTiff(), "heic", "mif1");
        List<byte[]> broken = new ArrayList<>();
        for (int cut : new int[]{9, 20, 40, png.length / 2, png.length - 5}) {
            broken.add(Arrays.copyOf(png, cut));
        }
        for (int cut : new int[]{12, 30, heic.length / 2, heic.length - 3}) {
            broken.add(Arrays.copyOf(heic, cut));
        }
        byte[] lying = heic.clone();
        int mdat = lying.length - (4 + 6 + sampleTiff().length) - 8;
        ByteBuffer.wrap(lying).putInt(mdat, Integer.MAX_VALUE); // mdat claims a size far past the end of the file
        broken.add(lying);
        byte[] badOffset = heic.clone();
        ByteBuffer.wrap(badOffset).putInt(mdat + 8, Integer.MAX_VALUE); // Exif item's TIFF offset past the item
        broken.add(badOffset);
        for (byte[] b : broken) {
            assertDoesNotThrow(() -> ExifReader.read(b));
        }
    }
}
