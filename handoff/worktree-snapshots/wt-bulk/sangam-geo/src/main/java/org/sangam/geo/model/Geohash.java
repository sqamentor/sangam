// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.model;

import org.sangam.geo.GeoException;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Geohash (base-32, public-domain algorithm). Used as the portable spatial key: every feature stores the geohash of its
 * centroid and the set of cells that cover its bounding box; a point query looks up the point's prefixes.
 */
public final class Geohash {

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";
    public static final int MAX_PRECISION = 12;

    private Geohash() {
    }

    public static String encode(GeoPoint p, int precision) {
        return encode(p.lat(), p.lon(), precision);
    }

    public static String encode(double lat, double lon, int precision) {
        if (precision < 1 || precision > MAX_PRECISION) {
            throw new GeoException("INVALID_PRECISION", "geohash precision must be 1.." + MAX_PRECISION);
        }
        double[] la = {-90, 90}, lo = {-180, 180};
        StringBuilder sb = new StringBuilder(precision);
        boolean even = true;
        int bit = 0, ch = 0;
        while (sb.length() < precision) {
            double[] r = even ? lo : la;
            double v = even ? lon : lat;
            double mid = (r[0] + r[1]) / 2;
            if (v >= mid) {
                ch = (ch << 1) | 1;
                r[0] = mid;
            } else {
                ch = ch << 1;
                r[1] = mid;
            }
            even = !even;
            if (++bit == 5) {
                sb.append(BASE32.charAt(ch));
                bit = 0;
                ch = 0;
            }
        }
        return sb.toString();
    }

    public static BBox decodeBox(String hash) {
        double[] la = {-90, 90}, lo = {-180, 180};
        boolean even = true;
        for (char c : hash.toCharArray()) {
            int cd = BASE32.indexOf(c);
            if (cd < 0) {
                throw new GeoException("INVALID_GEOHASH", "invalid geohash: " + hash);
            }
            for (int mask = 16; mask > 0; mask >>= 1) {
                double[] r = even ? lo : la;
                double mid = (r[0] + r[1]) / 2;
                if ((cd & mask) != 0) {
                    r[0] = mid;
                } else {
                    r[1] = mid;
                }
                even = !even;
            }
        }
        return new BBox(la[0], lo[0], la[1], lo[1]);
    }

    /** Cell height/width in degrees at a precision. */
    public static double[] cellSizeDeg(int precision) {
        int bits = precision * 5;
        int lonBits = (bits + 1) / 2, latBits = bits / 2;
        return new double[]{180.0 / (1L << latBits), 360.0 / (1L << lonBits)};
    }

    /**
     * The coarsest-sufficient precision whose cells cover the box with at most {@code maxCells} cells, and the cells.
     */
    public static Set<String> cover(BBox box, int maxCells) {
        for (int p = MAX_PRECISION; p >= 1; p--) {
            double[] size = cellSizeDeg(p);
            long rows = (long) Math.ceil(box.heightDeg() / size[0]) + 1;
            long cols = (long) Math.ceil(box.widthDeg() / size[1]) + 1;
            if (rows * cols <= maxCells) {
                return coverAt(box, p);
            }
        }
        return coverAt(box, 1);
    }

    public static Set<String> coverAt(BBox box, int precision) {
        double[] size = cellSizeDeg(precision);
        Set<String> cells = new LinkedHashSet<>();
        for (double lat = box.minLat(); ; lat += size[0]) {
            double la = Math.min(lat, box.maxLat());
            for (double lon = box.minLon(); ; lon += size[1]) {
                double lo = Math.min(lon, box.maxLon());
                cells.add(encode(la, lo, precision));
                if (lo >= box.maxLon()) {
                    break;
                }
            }
            if (la >= box.maxLat()) {
                break;
            }
        }
        return cells;
    }

    /** All prefixes of the point's full-precision geohash (lengths 1..12): the lookup keys for cell containment. */
    public static Set<String> prefixes(GeoPoint p) {
        String full = encode(p, MAX_PRECISION);
        Set<String> out = new LinkedHashSet<>();
        for (int i = 1; i <= MAX_PRECISION; i++) {
            out.add(full.substring(0, i));
        }
        return out;
    }
}
