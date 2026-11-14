// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Decision 183 offline smoke proof (no network, no implementation guide package): the HAPI FHIR 8.12.1 validator runs
 * on this module's test class path with jakarta-regexp and sqlite-jdbc excluded and the elected javassist version, a
 * base R4 Patient is valid, a wrong code fails, and a broken conformance resource is refused. Written in the coding
 * phase, NOT_RUN.
 */
class HapiFhirProfileValidationSmokeTest {

    @Test
    void theExcludedJarsAreAbsentAndTheElectedVersionIsUsed() throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.apache.regexp.RE"), "jakarta-regexp excluded");
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.sqlite.JDBC"), "sqlite-jdbc excluded");
        assertEquals("3.33.0-GA", Class.forName("javassist.CtClass").getField("version").get(null),
                "decision 117 javassist election");
        assertTrue(FhirProfileValidations.available(), "HAPI is on this module's own test class path");
    }

    /** Decision 263: xmlpull 1.1.3.1 is the only org.xmlpull.v1 API on the class path; the parser is mxparser. */
    @Test
    void oneXmlPullApiAndTheMxParserFromTheFactory() throws Exception {
        for (String c : List.of("XmlPullParser", "XmlPullParserException", "XmlPullParserFactory")) {
            List<java.net.URL> copies = java.util.Collections.list(getClass().getClassLoader()
                    .getResources("org/xmlpull/v1/" + c + ".class"));
            assertEquals(1, copies.size(), c + " copies: " + copies);
            assertTrue(copies.get(0).toString().contains("xmlpull-1.1.3.1"), copies.get(0).toString());
        }
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.xmlpull.mxp1.MXParser"),
                "xpp3_min and org.ogce xpp3 are not on the class path");
        assertEquals("io.github.xstream.mxparser.MXParser",
                org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser().getClass().getName());
    }

    @Test
    void aBaseR4PatientIsValidWithoutTheExcludedJars() {
        FhirProfileValidation v = FhirProfileValidations.create(List.of());
        FhirProfileValidation.Result r = v.validate(
                "{\"resourceType\":\"Patient\",\"gender\":\"unknown\",\"name\":[{\"family\":\"Test\"}]}");
        assertTrue(r.valid(), "issues: " + r.issues());
    }

    @Test
    void aCodeOutsideTheRequiredValueSetIsNotValid() {
        FhirProfileValidation v = FhirProfileValidations.create(List.of());
        FhirProfileValidation.Result r = v.validate("{\"resourceType\":\"Patient\",\"gender\":\"not-a-gender\"}");
        assertFalse(r.valid(), "issues: " + r.issues());
        assertTrue(r.issues().stream().anyMatch(FhirProfileValidation.Issue::blocking));
    }

    @Test
    void aBrokenConformanceResourceIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> FhirProfileValidations.create(List.of("{\"resourceType\":")));
    }

    @Test
    void anUnknownSeverityNeverPasses() {
        assertTrue(new FhirProfileValidation.Issue(FhirProfileValidation.Severity.ERROR, null, null, null).blocking());
        assertFalse(new FhirProfileValidation.Result(List.of(new FhirProfileValidation.Issue(
                FhirProfileValidation.Severity.FATAL, "", "", "x"))).valid());
        assertEquals(FhirProfileValidation.Severity.ERROR,
                org.sangam.connectors.health.validation.hapi.HapiFhirProfileValidation.severity(null));
    }
}
