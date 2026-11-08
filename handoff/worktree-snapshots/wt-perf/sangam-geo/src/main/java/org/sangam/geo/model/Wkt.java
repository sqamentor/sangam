// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.model;

import org.sangam.geo.GeoException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Well-Known Text reader/writer for the {@link Geometry} subset. Accepts an optional EWKT {@code SRID=4326;} prefix
 * (other SRIDs are rejected: Sangam stores WGS-84 only) and ignores Z/M ordinates.
 */
public final class Wkt {

    private Wkt() {
    }

    public static Geometry parse(String text) {
        if (text == null || text.isBlank()) {
            throw new GeoException("INVALID_WKT", "empty WKT");
        }
        String s = text.strip();
        if (s.regionMatches(true, 0, "SRID=", 0, 5)) {
            int semi = s.indexOf(';');
            String srid = s.substring(5, semi).strip();
            if (!"4326".equals(srid)) {
                throw new GeoException("UNSUPPORTED_SRID", "only SRID 4326 is stored; got " + srid);
            }
            s = s.substring(semi + 1).strip();
        }
        Parser p = new Parser(s);
        Geometry g = p.geometry();
        p.skipWs();
        if (p.pos < s.length()) {
            throw new GeoException("INVALID_WKT", "trailing text at " + p.pos);
        }
        return g;
    }

    public static String write(Geometry g) {
        StringBuilder sb = new StringBuilder();
        switch (g) {
            case Geometry.Point p -> sb.append("POINT (").append(coord(p.point())).append(')');
            case Geometry.MultiPoint mp -> {
                sb.append("MULTIPOINT (");
                for (int i = 0; i < mp.points().size(); i++) {
                    sb.append(i == 0 ? "" : ", ").append('(').append(coord(mp.points().get(i))).append(')');
                }
                sb.append(')');
            }
            case Geometry.LineString ls -> sb.append("LINESTRING ").append(ring(ls.points()));
            case Geometry.Polygon poly -> sb.append("POLYGON ").append(rings(poly.rings()));
            case Geometry.MultiPolygon mp -> {
                sb.append("MULTIPOLYGON (");
                for (int i = 0; i < mp.polygons().size(); i++) {
                    sb.append(i == 0 ? "" : ", ").append(rings(mp.polygons().get(i).rings()));
                }
                sb.append(')');
            }
        }
        return sb.toString();
    }

    private static String coord(GeoPoint p) {
        return fmt(p.lon()) + " " + fmt(p.lat());
    }

    static String fmt(double d) {
        String s = String.format(Locale.ROOT, "%.8f", d);
        s = s.indexOf('.') >= 0 ? s.replaceAll("0+$", "") : s;
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static String ring(List<GeoPoint> pts) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < pts.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(coord(pts.get(i)));
        }
        return sb.append(')').toString();
    }

    private static String rings(List<List<GeoPoint>> rs) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < rs.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(ring(rs.get(i)));
        }
        return sb.append(')').toString();
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        Geometry geometry() {
            String word = word().toUpperCase(Locale.ROOT);
            skipWs();
            String dim = peekWord().toUpperCase(Locale.ROOT);
            if (dim.equals("Z") || dim.equals("M") || dim.equals("ZM")) {
                word();
            }
            return switch (word) {
                case "POINT" -> {
                    expect('(');
                    GeoPoint p = point();
                    expect(')');
                    yield new Geometry.Point(p);
                }
                case "MULTIPOINT" -> {
                    expect('(');
                    List<GeoPoint> pts = new ArrayList<>();
                    do {
                        skipWs();
                        if (peek() == '(') {
                            expect('(');
                            pts.add(point());
                            expect(')');
                        } else {
                            pts.add(point());
                        }
                    } while (comma());
                    expect(')');
                    yield new Geometry.MultiPoint(pts);
                }
                case "LINESTRING" -> new Geometry.LineString(points());
                case "POLYGON" -> new Geometry.Polygon(ringList());
                case "MULTIPOLYGON" -> {
                    expect('(');
                    List<Geometry.Polygon> polys = new ArrayList<>();
                    do {
                        polys.add(new Geometry.Polygon(ringList()));
                    } while (comma());
                    expect(')');
                    yield new Geometry.MultiPolygon(polys);
                }
                default -> throw new GeoException("UNSUPPORTED_GEOMETRY", "unsupported WKT type: " + word);
            };
        }

        List<List<GeoPoint>> ringList() {
            expect('(');
            List<List<GeoPoint>> rings = new ArrayList<>();
            do {
                rings.add(points());
            } while (comma());
            expect(')');
            return rings;
        }

        List<GeoPoint> points() {
            expect('(');
            List<GeoPoint> pts = new ArrayList<>();
            do {
                pts.add(point());
            } while (comma());
            expect(')');
            return pts;
        }

        GeoPoint point() {
            double x = number();
            double y = number();
            skipWs();
            while (pos < s.length() && (Character.isDigit(peek()) || peek() == '-' || peek() == '.')) {
                number();
                skipWs();
            }
            return new GeoPoint(y, x);
        }

        double number() {
            skipWs();
            int start = pos;
            while (pos < s.length() && "+-.0123456789eE".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            if (start == pos) {
                throw new GeoException("INVALID_WKT", "number expected at " + pos);
            }
            return Double.parseDouble(s.substring(start, pos));
        }

        boolean comma() {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ',') {
                pos++;
                return true;
            }
            return false;
        }

        void expect(char c) {
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != c) {
                throw new GeoException("INVALID_WKT", "'" + c + "' expected at " + pos);
            }
            pos++;
        }

        char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        String word() {
            skipWs();
            int start = pos;
            while (pos < s.length() && Character.isLetter(s.charAt(pos))) {
                pos++;
            }
            return s.substring(start, pos);
        }

        String peekWord() {
            int save = pos;
            String w = word();
            pos = save;
            return w;
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }
    }
}
