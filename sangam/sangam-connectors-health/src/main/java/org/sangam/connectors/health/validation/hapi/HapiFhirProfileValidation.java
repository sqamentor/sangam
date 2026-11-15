// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.validation.hapi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.PrePopulatedValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IBaseResource;

import org.sangam.connectors.health.validation.FhirProfileValidation;

/**
 * HAPI FHIR 8.12.1 implementation of {@link FhirProfileValidation} (decision 183). Loaded only by
 * {@code FhirProfileValidations} after it has checked that HAPI is on the class path; no other class of this module
 * refers to it. The chain is the one proven by the offline smoke run of decision 183: base R4 definitions, the supplied conformance
 * resources, snapshot generation, in-memory terminology and the common code systems. Only resources the caller passes
 * are used; nothing is fetched from the network.
 */
public final class HapiFhirProfileValidation implements FhirProfileValidation {

    private final FhirValidator validator;

    /**
     * @param conformanceResourcesJson StructureDefinition, ValueSet and CodeSystem resources as JSON
     * @throws IllegalArgumentException when one of them cannot be parsed
     */
    public HapiFhirProfileValidation(List<String> conformanceResourcesJson) {
        FhirContext ctx = FhirContext.forR4Cached();
        PrePopulatedValidationSupport supplied = new PrePopulatedValidationSupport(ctx);
        int index = 0;
        for (String json : conformanceResourcesJson) {
            Objects.requireNonNull(json, "conformance resource");
            IBaseResource resource;
            try {
                resource = ctx.newJsonParser().parseResource(json);
            } catch (DataFormatException e) {
                throw new IllegalArgumentException("conformance resource " + index + " is not valid FHIR R4 JSON", e);
            }
            supplied.addResource(resource);
            index++;
        }
        ValidationSupportChain chain = new ValidationSupportChain(new DefaultProfileValidationSupport(ctx), supplied,
                new SnapshotGeneratingValidationSupport(ctx), new InMemoryTerminologyServerValidationSupport(ctx),
                new CommonCodeSystemsTerminologyService(ctx));
        FhirValidator v = ctx.newValidator();
        v.registerValidatorModule(new FhirInstanceValidator(chain));
        this.validator = v;
    }

    @Override
    public Result validate(String resourceJson) {
        Objects.requireNonNull(resourceJson, "resourceJson");
        ValidationResult r = validator.validateWithResult(resourceJson);
        List<Issue> issues = new ArrayList<>();
        for (SingleValidationMessage m : r.getMessages()) {
            issues.add(new Issue(severity(m.getSeverity()), m.getLocationString(), m.getMessageId(), m.getMessage()));
        }
        return new Result(issues);
    }

    /** Unknown or missing severities count as ERROR, so an unrecognised outcome never passes. */
    public static Severity severity(ResultSeverityEnum s) {
        if (s == null) {
            return Severity.ERROR;
        }
        return switch (s) {
            case INFORMATION -> Severity.INFORMATION;
            case WARNING -> Severity.WARNING;
            case FATAL -> Severity.FATAL;
            default -> Severity.ERROR;
        };
    }
}
