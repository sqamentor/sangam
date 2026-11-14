import sys
root = sys.argv[1]


def patch(path, pairs):
    s = open(path, encoding='utf-8').read()
    for old, new in pairs:
        assert s.count(old) == 1, (path, old[:70], s.count(old))
        s = s.replace(old, new)
    open(path, 'w', encoding='utf-8', newline='\n').write(s)


ev = root + '/src/main/java/org/sangam/field/evidence/'
patch(ev + 'ExifReader.java', [
    ('''import java.nio.ByteBuffer;
''', '''import com.drew.imaging.heif.HeifMetadataReader;
import com.drew.imaging.png.PngMetadataReader;
import com.drew.lang.Rational;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
'''),
    ('''import java.util.Map;
''', '''import java.util.Map;
import java.util.Set;
'''),
    ('''/**
 * Minimal, allocation-bounded JPEG EXIF reader (pure Java, no dependency). Reads only what the anti-spoofing checks
 * need: Make, Model, Software, DateTime / DateTimeOriginal, OffsetTimeOriginal, and GPS latitude/longitude, date and
 * time stamp. Malformed or hostile input never throws: the result is simply missing fields ({@code present=false}).
 */''', '''/**
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
 */'''),
    ('''    private static final int MAX_SCAN = 256 * 1024;
''', '''    private static final int MAX_SCAN = 256 * 1024;

    /** Upper bound for PNG and HEIF parsing (whole-file container formats). */
    static final int MAX_CONTAINER_BYTES = 64 * 1024 * 1024;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\\r', '\\n', 0x1A, '\\n'};

    /** ISO base media brands of HEIF still images (ISO/IEC 23008-12) and AVIF, which shares the container. */
    private static final Set<String> HEIF_BRANDS = Set.of("mif1", "msf1", "heic", "heix", "heim", "heis", "hevc", "hevx", "avif", "avis");

    private static final int[] IFD0_STRINGS = {0x010F, 0x0110, 0x0131, 0x0132};
    private static final int[] EXIF_STRINGS = {0x9003, 0x9011};
    private static final int[] GPS_STRINGS = {0x01, 0x03, 0x1D};
    private static final int[] GPS_RATIONALS = {0x02, 0x04, 0x07};
'''),
    ('''    public static ExifData read(byte[] jpeg) {
        try {
            return parse(jpeg);
        } catch (RuntimeException e) {
            return ExifData.NONE;
        }
    }
''', '''    /** EXIF of a JPEG, PNG or HEIF/HEIC image; {@link ExifData#NONE} for any other format or unreadable input. */
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
                int nul = v.indexOf('\\0');
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
'''),
    ('''        Map<Integer, Object> ifd0 = ifd(t, t.getInt(4));
        Map<Integer, Object> exif = ifd0.get(0x8769) instanceof Long off ? ifd(t, off.intValue()) : Map.of();
        Map<Integer, Object> gps = ifd0.get(0x8825) instanceof Long off ? ifd(t, off.intValue()) : Map.of();

        String dto''', '''        Map<Integer, Object> ifd0 = ifd(t, t.getInt(4));
        Map<Integer, Object> exif = ifd0.get(0x8769) instanceof Long off ? ifd(t, off.intValue()) : Map.of();
        Map<Integer, Object> gps = ifd0.get(0x8825) instanceof Long off ? ifd(t, off.intValue()) : Map.of();
        return interpret(ifd0, exif, gps);
    }

    /** Turns IFD0, Exif and GPS tag maps (ASCII as String, RATIONAL as double[]) into the checks' fields. */
    private static ExifData interpret(Map<Integer, Object> ifd0, Map<Integer, Object> exif, Map<Integer, Object> gps) {
        String dto'''),
])
patch(ev + 'EvidenceService.java', [
    ('''        boolean jpeg = bytes.length > 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8;
        ExifReader.ExifData exif = jpeg ? ExifReader.read(bytes) : ExifReader.ExifData.NONE;
''', '''        ExifReader.ExifData exif = ExifReader.read(bytes); // JPEG, PNG and HEIF/HEIC (M-132); NONE for other formats
'''),
])
patch(root + '/pom.xml', [
    ('''    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
    </dependency>
''', '''    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
    </dependency>
    <!-- M-132: EXIF of PNG (eXIf) and HEIF/HEIC evidence. Apache-2.0; brings com.adobe.xmp:xmpcore 6.1.11 (BSD-3-Clause).
         Owner decisions 56 and 60. The version is pinned here until the root pom dependencyManagement entry lands
         (REQUEST to the integrator); then this <version> is removed. -->
    <dependency>
      <groupId>com.drewnoakes</groupId>
      <artifactId>metadata-extractor</artifactId>
      <version>2.22.0</version>
    </dependency>
'''),
])
print('ok')
