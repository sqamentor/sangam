// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.validation;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

/**
 * Entry point of the optional FHIR profile validation capability (decisions 183 and 187). HAPI FHIR is an optional
 * dependency of this module: it is never on the class path of a module that depends on this one unless a deployment
 * adds it. This class touches no HAPI type; it loads the HAPI-backed implementation by name only after checking that
 * every required HAPI class is present, and otherwise refuses with {@link CapabilityMissingException}
 * ({@code CAPABILITY_MISSING}). Nothing ever reports a resource as valid without a validator.
 */
public final class FhirProfileValidations {

    /** Capability id in {@code CAPABILITY_MISSING} messages. */
    public static final String CAPABILITY = "connectors-health.fhir-profile-validation";

    /** The classes the HAPI implementation needs at run time (validator, R4 structures, cache provider). */
    static final List<String> REQUIRED_CLASSES = List.of(
            "ca.uhn.fhir.context.FhirContext",
            "org.hl7.fhir.r4.model.Patient",
            "org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator",
            "ca.uhn.fhir.sl.cache.caffeine.CacheProvider");

    static final String IMPLEMENTATION = "org.sangam.connectors.health.validation.hapi.HapiFhirProfileValidation";

    private FhirProfileValidations() {
    }

    /** True when every class the HAPI implementation needs is on the class path. */
    public static boolean available() {
        return missingClass() == null;
    }

    /**
     * A validator over the FHIR R4 base definitions plus the given conformance resources (StructureDefinition, ValueSet,
     * CodeSystem as JSON, for example from a tenant's implementation guide package).
     *
     * @throws CapabilityMissingException when HAPI is not installed
     * @throws IllegalArgumentException   when a conformance resource cannot be parsed
     */
    public static FhirProfileValidation create(List<String> conformanceResourcesJson) {
        String missing = missingClass();
        if (missing != null) {
            throw new CapabilityMissingException(CAPABILITY, "HAPI FHIR is not on the class path: " + missing);
        }
        List<String> resources = List.copyOf(conformanceResourcesJson);
        try {
            return (FhirProfileValidation) Class.forName(IMPLEMENTATION, true, FhirProfileValidations.class.getClassLoader())
                    .getConstructor(List.class).newInstance(resources);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            throw new IllegalStateException("FHIR profile validation could not start", e.getCause());
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new CapabilityMissingException(CAPABILITY, "HAPI FHIR could not be loaded: " + e.getClass().getName());
        }
    }

    private static String missingClass() {
        for (String name : REQUIRED_CLASSES) {
            try {
                Class.forName(name, false, FhirProfileValidations.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                return name;
            }
        }
        return null;
    }
}
