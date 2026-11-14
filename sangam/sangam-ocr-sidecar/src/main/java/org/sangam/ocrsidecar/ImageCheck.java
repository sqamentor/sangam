// SPDX-License-Identifier: Apache-2.0
package org.sangam.ocrsidecar;

/**
 * Checks the image before any engine sees it (decision 153: limits on what is processed). The media type is taken from
 * the magic bytes, never from the declared header alone, and must equal the declared one; the pixel count read from
 * the header (PNG IHDR, the first JPEG frame header, the first TIFF image directory) must be known and within the
 * limit, so a small file that decodes to a huge bitmap is refused before decoding.
 */
final class ImageCheck {

    /** Why the image is refused, with the HTTP status to answer. */
    static final class Refused extends Exception {
        private final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    private ImageCheck() {
    }

    /** The media type the bytes start with, or {@code null}. */
    static String detect(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 0x0d
                && b[5] == 0x0a && b[6] == 0x1a && b[7] == 0x0a) {
            return "image/png";
        }
        if (b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (b.length >= 4 && ((b[0] == 'I' && b[1] == 'I' && b[2] == 42 && b[3] == 0)
                || (b[0] == 'M' && b[1] == 'M' && b[2] == 0 && b[3] == 42))) {
            return "image/tiff";
        }
        return null;
    }

    /** Accepts the bytes as {@code declared} within {@code maxPixels}, or throws why not. */
    static void check(byte[] image, String declared, long maxPixels) throws Refused {
        String base = declared == null ? "" : declared.split(";", 2)[0].strip().toLowerCase(java.util.Locale.ROOT);
        String actual = detect(image);
        if (actual == null) {
            throw new Refused(415, "the body is not a PNG, JPEG or TIFF image");
        }
        if (!actual.equals(base)) {
            throw new Refused(415, "the body is " + actual + " but was declared as " + (base.isEmpty() ? "nothing" : base));
        }
        long[] size = switch (actual) {
            case "image/png" -> png(image);
            case "image/jpeg" -> jpeg(image);
            default -> tiff(image);
        };
        if (size == null || size[0] < 1 || size[1] < 1) {
            throw new Refused(422, "the image size cannot be read from its header");
        }
        if (size[0] * size[1] > maxPixels) {
            throw new Refused(413, "the image has " + size[0] + " x " + size[1] + " pixels, more than " + maxPixels);
        }
    }

    /** {width, height} from the IHDR chunk that must follow the signature. */
    static long[] png(byte[] b) {
        if (b.length < 24 || b[12] != 'I' || b[13] != 'H' || b[14] != 'D' || b[15] != 'R') {
            return null;
        }
        return new long[] {u32be(b, 16), u32be(b, 20)};
    }

    /** {width, height} from the first start-of-frame marker. */
    static long[] jpeg(byte[] b) {
        int i = 2;
        while (i + 4 <= b.length) {
            if ((b[i] & 0xff) != 0xff) {
                return null;
            }
            int marker = b[i + 1] & 0xff;
            if (marker == 0xff) {
                i++;
                continue;
            }
            if (marker == 0xd8 || (marker >= 0xd0 && marker <= 0xd7) || marker == 0x01) {
                i += 2;
                continue;
            }
            if (marker == 0xd9 || marker == 0xda) {
                return null; // end of image or scan data before any frame header
            }
            int length = u16be(b, i + 2);
            if (length < 2) {
                return null;
            }
            boolean sof = marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc;
            if (sof) {
                if (i + 9 > b.length) {
                    return null;
                }
                return new long[] {u16be(b, i + 7), u16be(b, i + 5)};
            }
            i += 2 + length;
        }
        return null;
    }

    /** {width, height} from tags 256 and 257 of the first image file directory. */
    static long[] tiff(byte[] b) {
        if (b.length < 8) {
            return null;
        }
        boolean le = b[0] == 'I';
        long ifd = u32(b, 4, le);
        if (ifd < 8 || ifd + 2 > b.length) {
            return null;
        }
        int at = (int) ifd;
        int entries = u16(b, at, le);
        long width = -1;
        long height = -1;
        for (int n = 0; n < entries; n++) {
            int e = at + 2 + n * 12;
            if (e + 12 > b.length) {
                return null;
            }
            int tag = u16(b, e, le);
            int type = u16(b, e + 2, le);
            long value = type == 3 ? u16(b, e + 8, le) : type == 4 ? u32(b, e + 8, le) : -1;
            if (tag == 256) {
                width = value;
            } else if (tag == 257) {
                height = value;
            }
        }
        return width < 0 || height < 0 ? null : new long[] {width, height};
    }

    private static int u16be(byte[] b, int at) {
        return ((b[at] & 0xff) << 8) | (b[at + 1] & 0xff);
    }

    private static long u32be(byte[] b, int at) {
        return ((long) (b[at] & 0xff) << 24) | ((b[at + 1] & 0xff) << 16) | ((b[at + 2] & 0xff) << 8) | (b[at + 3] & 0xff);
    }

    private static int u16(byte[] b, int at, boolean le) {
        return le ? (b[at] & 0xff) | ((b[at + 1] & 0xff) << 8) : u16be(b, at);
    }

    private static long u32(byte[] b, int at, boolean le) {
        if (!le) {
            return u32be(b, at);
        }
        return (b[at] & 0xff) | ((b[at + 1] & 0xff) << 8) | ((b[at + 2] & 0xff) << 16) | ((long) (b[at + 3] & 0xff) << 24);
    }
}
