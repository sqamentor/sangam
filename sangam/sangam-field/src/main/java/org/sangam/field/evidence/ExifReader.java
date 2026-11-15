// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.evidence;

import com.drew.imaging.heif.HeifMetadataReader;
import com.drew.imaging.png.PngMetadataReader;
import com.drew.lang.Rational;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The one EXIF reader of field evidence. Reads only what the anti-spoofing checks need: Make, Model, Software,
 * DateTime / DateTimeOriginal, OffsetTimeOriginal, and GPS latitude/longitude, date and time stamp. Malformed or
 * hostile input never throws: the result is simply missing fields ({@code present=false}).
 *
 * <ul>
 *   <li>JPEG: a minimal, allocation-bounded pure-Java parser of the APP1 {@code Exif} segment.</li>
 *   <li>PNG ({@code eXIf} chunk) and HEIF/HEIC ({@code Exif} item): container parsing by metadata-extractor (Apache-2.0,
 *       M-132); its EXIF directories are mapped to the same tag maps, so all three formats are interpreted by the
 *       same code ({@link #interpret}). Input above {@link #MAX_CONTAINER_BYTES} is not parsed.</li>
 * </ul>
 */
public final class ExifReader {

    /**
     * @param originalTime DateTimeOriginal interpreted with OffsetTimeOriginal when present (else {@code localTime} only)
     * @param localTime    DateTimeOriginal as written (camera local wall time, no zone)
     * @param gpsTime      GPS date + time stamp (always UTC)
     */
    public record ExifData(boolean present, String make, String model, String software, LocalDateTime localTime,
                           Instant originalTime, Double gpsLat, Double gpsLon, Instant gpsTime) {

        public static final ExifData NONE = new ExifData(false, null, null, null, null, null, null, null, null);

        public boolean hasGps() {
            return gpsLat != null && gpsLon != null;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("present", present);
            m.put("make", make);
            m.put("model", model);
            m.put("software", software);
            m.put("localTime", localTime == null ? null : localTime.toString());
            m.put("originalTime", originalTime == null ? null : originalTime.toString());
            m.put("gpsLat", gpsLat);
            m.put("gpsLon", gpsLon);
            m.put("gpsTime", gpsTime == null ? null : gpsTime.toString());
            return m;
        }
    }

    private static final DateTimeFormatter EXIF_TS = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");
    private static final int MAX_SCAN = 256 * 1024;

    /** Most values of one IFD entry that are read; an entry declaring more (a hostile 32-bit count) is skipped. */
    static final int MAX_VALUES = 4096;

    /** Upper bound for PNG and HEIF parsing (whole-file container formats). */
    static final int MAX_CONTAINER_BYTES = 64 * 1024 * 1024;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** ISO base media brands of HEIF still images (ISO/IEC 23008-12) and AVIF, which shares the container. */
    private static final Set<String> HEIF_BRANDS = Set.of("mif1", "msf1", "heic", "heix", "heim", "heis", "hevc", "hevx", "avif", "avis");

    private static final int[] IFD0_STRINGS = {0x010F, 0x0110, 0x0131, 0x0132};
    private static final int[] EXIF_STRINGS = {0x9003, 0x9011};
    private static final int[] GPS_STRINGS = {0x01, 0x03, 0x1D};
    private static final int[] GPS_RATIONALS = {0x02, 0x04, 0x07};

    private ExifReader() {
    }

    /** EXIF of a JPEG, PNG or HEIF/HEIC image; {@link ExifData#NONE} for any other format or unreadable input. */
    public static ExifData read(byte[] image) {
        try {
            if (image == null || image.length < 12) {
                return ExifData.NONE;
            }
            if ((image[0] & 0xFF) == 0xFF && (image[1] & 0xFF) == 0xD8) {
                return parse(image);
            }
            if (image.length > MAX_CONTAINER_BYTES) {
                return ExifData.NONE;
            }
            if (isPng(image)) {
                return fromDirectories(PngMetadataReader.readMetadata(new ByteArrayInputStream(image)));
            }
            if (isHeif(image)) {
                return fromDirectories(HeifMetadataReader.readMetadata(new ByteArrayInputStream(image)));
            }
            return ExifData.NONE;
        } catch (Exception | StackOverflowError e) {
            return ExifData.NONE;
        }
    }

    static boolean isPng(byte[] b) {
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (b[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    /** An ISO base media file whose first box is {@code ftyp} with a HEIF major or compatible brand. */
    static boolean isHeif(byte[] b) {
        if (!"ftyp".equals(new String(b, 4, 4, StandardCharsets.US_ASCII))) {
            return false;
        }
        long size = ByteBuffer.wrap(b, 0, 4).getInt() & 0xFFFFFFFFL;
        int end = (int) Math.min(Math.max(size, 16), Math.min(b.length, 4096));
        for (int p = 8; p + 4 <= end; p += 4) {
            if (p == 12) {
                continue; // minor version
            }
            if (HEIF_BRANDS.contains(new String(b, p, 4, StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    /** Maps the library's EXIF directories to the tag maps of the JPEG path. */
    private static ExifData fromDirectories(Metadata m) {
        Directory ifd0 = m.getFirstDirectoryOfType(ExifIFD0Directory.class);
        Directory exif = m.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        Directory gps = m.getFirstDirectoryOfType(GpsDirectory.class);
        if (ifd0 == null && exif == null && gps == null) {
            return ExifData.NONE;
        }
        return interpret(tags(ifd0, IFD0_STRINGS, new int[0]), tags(exif, EXIF_STRINGS, new int[0]), tags(gps, GPS_STRINGS, GPS_RATIONALS));
    }

    private static Map<Integer, Object> tags(Directory d, int[] strings, int[] rationals) {
        Map<Integer, Object> out = new LinkedHashMap<>();
        if (d == null) {
            return out;
        }
        for (int tag : strings) {
            String v = d.getString(tag);
            if (v != null) {
                int nul = v.indexOf('\0');
                out.put(tag, nul >= 0 ? v.substring(0, nul) : v);
            }
        }
        for (int tag : rationals) {
            Rational[] r = d.getRationalArray(tag);
            if (r != null && r.length <= 16) {
                double[] v = new double[r.length];
                for (int i = 0; i < r.length; i++) {
                    v[i] = r[i].getDenominator() == 0 ? 0 : r[i].doubleValue();
                }
                out.put(tag, v);
            }
        }
        return out;
    }

    private static ExifData parse(byte[] b) {
        if (b == null || b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) {
            return ExifData.NONE;
        }
        int pos = 2;
        int limit = Math.min(b.length, MAX_SCAN);
        while (pos + 4 <= limit) {
            if ((b[pos] & 0xFF) != 0xFF) {
                return ExifData.NONE;
            }
            int marker = b[pos + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }
            int len = ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
            if (marker == 0xE1 && len > 8 && pos + 2 + len <= b.length
                    && new String(b, pos + 4, 4, StandardCharsets.US_ASCII).equals("Exif")) {
                return tiff(ByteBuffer.wrap(b, pos + 10, len - 8).slice());
            }
            pos += 2 + len;
        }
        return ExifData.NONE;
    }

    private static ExifData tiff(ByteBuffer t) {
        byte o0 = t.get(0);
        t.order(o0 == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        if ((t.getShort(2) & 0xFFFF) != 42) {
            return ExifData.NONE;
        }
        Map<Integer, Object> ifd0 = ifd(t, t.getInt(4));
        Map<Integer, Object> exif = ifd0.get(0x8769) instanceof Long off ? ifd(t, off.intValue()) : Map.of();
        Map<Integer, Object> gps = ifd0.get(0x8825) instanceof Long off ? ifd(t, off.intValue()) : Map.of();
        return interpret(ifd0, exif, gps);
    }

    /** Turns IFD0, Exif and GPS tag maps (ASCII as String, RATIONAL as double[]) into the checks' fields. */
    private static ExifData interpret(Map<Integer, Object> ifd0, Map<Integer, Object> exif, Map<Integer, Object> gps) {
        String dto = str(exif.get(0x9003));
        if (dto == null) {
            dto = str(ifd0.get(0x0132));
        }
        LocalDateTime local = null;
        Instant original = null;
        if (dto != null) {
            try {
                local = LocalDateTime.parse(dto.strip(), EXIF_TS);
                String off = str(exif.get(0x9011));
                if (off != null && off.matches("[+-]\\d{2}:\\d{2}")) {
                    original = local.toInstant(ZoneOffset.of(off));
                }
            } catch (RuntimeException ignored) {
                local = null;
            }
        }
        Double lat = coord(gps.get(2), str(gps.get(1)), "S");
        Double lon = coord(gps.get(4), str(gps.get(3)), "W");
        Instant gpsTime = null;
        if (gps.get(7) instanceof double[] hms && hms.length == 3 && str(gps.get(0x1D)) != null) {
            try {
                String d = str(gps.get(0x1D)).strip().replace(':', '-');
                gpsTime = LocalDateTime.parse(d + "T00:00:00").toInstant(ZoneOffset.UTC)
                        .plusSeconds((long) (hms[0] * 3600 + hms[1] * 60 + hms[2]));
            } catch (RuntimeException ignored) {
                gpsTime = null;
            }
        }
        return new ExifData(true, str(ifd0.get(0x010F)), str(ifd0.get(0x0110)), str(ifd0.get(0x0131)), local, original, lat, lon, gpsTime);
    }

    private static Double coord(Object dms, String ref, String negative) {
        if (!(dms instanceof double[] v) || v.length != 3) {
            return null;
        }
        double d = v[0] + v[1] / 60 + v[2] / 3600;
        if (ref != null && ref.strip().equalsIgnoreCase(negative)) {
            d = -d;
        }
        return Double.isFinite(d) ? d : null;
    }

    private static String str(Object o) {
        return o instanceof String s ? s : null;
    }

    /** Reads one IFD: ASCII → String, SHORT/LONG (count 1) → Long, RATIONAL → double[]. */
    private static Map<Integer, Object> ifd(ByteBuffer t, int off) {
        Map<Integer, Object> out = new LinkedHashMap<>();
        if (off <= 0 || off + 2 > t.limit()) {
            return out;
        }
        int n = t.getShort(off) & 0xFFFF;
        for (int i = 0; i < Math.min(n, 512); i++) {
            int e = off + 2 + i * 12;
            if (e + 12 > t.limit()) {
                break;
            }
            int tag = t.getShort(e) & 0xFFFF;
            int type = t.getShort(e + 2) & 0xFFFF;
            long count = t.getInt(e + 4) & 0xFFFFFFFFL;
            int size = switch (type) {
                case 1, 2, 7 -> 1;
                case 3 -> 2;
                case 4, 9 -> 4;
                case 5, 10 -> 8;
                default -> 0;
            };
            if (size == 0 || count > MAX_VALUES) {
                continue;
            }
            // bounded above, so the 32-bit count of the file is an int from here on (no long/int loop comparison)
            int values = Math.toIntExact(count);
            long total = (long) size * values;
            int vo = total <= 4 ? e + 8 : t.getInt(e + 8);
            if (vo < 0 || vo + total > t.limit()) {
                continue;
            }
            switch (type) {
                case 2 -> {
                    byte[] s = new byte[values];
                    for (int k = 0; k < values; k++) {
                        s[k] = t.get(vo + k);
                    }
                    int end = 0;
                    while (end < s.length && s[end] != 0) {
                        end++;
                    }
                    out.put(tag, new String(s, 0, end, StandardCharsets.UTF_8));
                }
                case 3 -> out.put(tag, (long) (t.getShort(vo) & 0xFFFF));
                case 4 -> out.put(tag, t.getInt(vo) & 0xFFFFFFFFL);
                case 5, 10 -> {
                    double[] r = new double[values];
                    for (int k = 0; k < values; k++) {
                        long num = t.getInt(vo + k * 8) & 0xFFFFFFFFL, den = t.getInt(vo + k * 8 + 4) & 0xFFFFFFFFL;
                        if (type == 10) {
                            num = t.getInt(vo + k * 8);
                            den = t.getInt(vo + k * 8 + 4);
                        }
                        r[k] = den == 0 ? 0 : (double) num / den;
                    }
                    out.put(tag, r);
                }
                default -> {
                    // BYTE / UNDEFINED / SLONG not needed by the checks
                }
            }
        }
        return out;
    }
}
