// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.evidence;

import org.sangam.field.evidence.CapturePacket.GpsFix;
import org.sangam.field.template.FieldTemplate.EvidenceRules;
import org.sangam.geo.geofence.GeofenceResult;
import org.sangam.geo.model.GeoMath;
import org.sangam.geo.model.GeoPoint;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Pure anti-spoofing checks (N05): device clock vs server receipt, capture time vs visit window, fix accuracy,
 * mock-location flag, geofence, EXIF consistency (time and position), photo age and duplicate photo hash.
 */
public final class IntegrityChecks {

    private IntegrityChecks() {
    }

    /** Visit window and clock skew. */
    public static void time(IntegrityReport.Builder b, Instant deviceTime, Instant receivedAt, Instant windowStart, Instant windowEnd,
                            long maxSkewSeconds) {
        if (deviceTime == null) {
            b.fail("DEVICE_TIME_MISSING", "packet has no device time");
            return;
        }
        if (deviceTime.isAfter(receivedAt.plusSeconds(maxSkewSeconds))) {
            b.fail("DEVICE_CLOCK_AHEAD", "device time " + deviceTime + " is after server receipt " + receivedAt);
        } else {
            b.pass("DEVICE_CLOCK", "device time not ahead of receipt");
        }
        if (windowStart != null && deviceTime.isBefore(windowStart.minusSeconds(maxSkewSeconds))
                || windowEnd != null && deviceTime.isAfter(windowEnd.plusSeconds(maxSkewSeconds))) {
            b.fail("OUTSIDE_VISIT_WINDOW", "captured at " + deviceTime + ", window " + windowStart + " .. " + windowEnd);
        } else if (windowStart != null || windowEnd != null) {
            b.pass("VISIT_WINDOW", "captured inside the visit window");
        }
    }

    public static void fix(IntegrityReport.Builder b, GpsFix fix, EvidenceRules rules, boolean fixRequired) {
        if (fix == null) {
            if (fixRequired) {
                b.fail("NO_GPS_FIX", "no position fix");
            }
            return;
        }
        Double maxAcc = rules == null ? null : rules.maxAccuracyMeters();
        if (maxAcc != null && (fix.accuracyM() == null || fix.accuracyM() > maxAcc)) {
            b.fail("ACCURACY_TOO_LOW", "accuracy " + fix.accuracyM() + " m > " + maxAcc + " m");
        } else if (maxAcc != null) {
            b.pass("ACCURACY", "accuracy " + fix.accuracyM() + " m");
        }
        if (fix.mockLocation()) {
            if (rules != null && rules.rejectMockLocation()) {
                b.fail("MOCK_LOCATION", "device reported a mock / simulated location provider");
            } else {
                b.warn("MOCK_LOCATION", "device reported a mock / simulated location provider");
            }
        }
    }

    public static void geofence(IntegrityReport.Builder b, GeofenceResult r, boolean required) {
        if (r == null) {
            return;
        }
        String detail = r.reason() + " (" + Math.round(r.signedDistanceM()) + " m from edge, accuracy " + r.accuracyM() + " m)";
        switch (r.status()) {
            case INSIDE -> b.pass("GEOFENCE", detail);
            case OUTSIDE -> {
                if (required) {
                    b.fail("GEOFENCE_OUTSIDE", detail);
                } else {
                    b.warn("GEOFENCE_OUTSIDE", detail);
                }
            }
            case INDETERMINATE -> b.warn("GEOFENCE_INDETERMINATE", detail);
        }
    }

    /** Photo checks: hash, EXIF time/position vs declared capture, photo age, duplicate. */
    public static void media(IntegrityReport.Builder b, String declaredSha, String actualSha, ExifReader.ExifData exif,
                             Instant capturedAt, GpsFix declaredFix, Instant packetTime, EvidenceRules rules, boolean duplicate,
                             long defaultSkewSeconds) {
        if (declaredSha != null && !declaredSha.equalsIgnoreCase(actualSha)) {
            b.reject("HASH_MISMATCH", "declared " + declaredSha + " but bytes hash to " + actualSha);
        } else {
            b.pass("HASH", actualSha);
        }
        EvidenceRules r = rules == null ? EvidenceRules.lenient() : rules;
        long skew = r.maxClockSkewSeconds() == null ? defaultSkewSeconds : r.maxClockSkewSeconds();
        if (duplicate) {
            if (r.rejectDuplicatePhoto()) {
                b.fail("DUPLICATE_PHOTO", "the same image bytes were already submitted as other evidence");
            } else {
                b.warn("DUPLICATE_PHOTO", "the same image bytes were already submitted as other evidence");
            }
        }
        if (exif == null || !exif.present()) {
            if (r.exifRequired()) {
                b.fail("EXIF_MISSING", "image carries no EXIF metadata");
            }
        } else {
            Instant exifTime = exif.originalTime() != null ? exif.originalTime() : exif.gpsTime();
            if (exifTime != null && capturedAt != null) {
                long d = Math.abs(Duration.between(exifTime, capturedAt).toSeconds());
                if (d > skew) {
                    b.fail("EXIF_TIME_MISMATCH", "EXIF time " + exifTime + " vs declared " + capturedAt);
                } else {
                    b.pass("EXIF_TIME", "within " + skew + " s");
                }
            } else if (exif.localTime() != null && capturedAt != null) {
                // camera wall time without an offset: only a coarse check is possible (any zone offset within ±14 h)
                long d = Math.abs(Duration.between(exif.localTime().toInstant(ZoneOffset.UTC), capturedAt).toSeconds());
                if (d > 14 * 3600 + skew) {
                    b.fail("EXIF_TIME_MISMATCH", "EXIF local time " + exif.localTime() + " vs declared " + capturedAt);
                } else {
                    b.warn("EXIF_TIME_NO_OFFSET", "EXIF time has no offset; only a coarse check was possible");
                }
            }
            if (exif.hasGps() && declaredFix != null && r.exifMaxDistanceM() != null) {
                double dist = GeoMath.haversineMeters(new GeoPoint(exif.gpsLat(), exif.gpsLon()),
                        new GeoPoint(declaredFix.lat(), declaredFix.lon()));
                if (dist > r.exifMaxDistanceM()) {
                    b.fail("EXIF_GPS_MISMATCH", "EXIF position is " + Math.round(dist) + " m from the reported fix");
                } else {
                    b.pass("EXIF_GPS", Math.round(dist) + " m from the reported fix");
                }
            } else if (r.exifRequired() && !exif.hasGps()) {
                b.fail("EXIF_GPS_MISSING", "image EXIF has no GPS position");
            }
        }
        if (r.maxPhotoAgeMinutes() != null && capturedAt != null && packetTime != null
                && Duration.between(capturedAt, packetTime).toMinutes() > r.maxPhotoAgeMinutes()) {
            b.fail("PHOTO_TOO_OLD", "photo taken " + Duration.between(capturedAt, packetTime).toMinutes()
                    + " min before submission (max " + r.maxPhotoAgeMinutes() + ")");
        }
    }
}
