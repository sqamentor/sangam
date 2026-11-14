import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.PrePopulatedValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.utilities.npm.NpmPackage;

import java.io.FileInputStream;
import java.io.InputStream;

/** Evaluation smoke test only (W4-DL D-2). Args: path to an IG .tgz (US Core) or "-" for none. */
public class HapiSmoke {
  public static void main(String[] args) throws Exception {
    FhirContext ctx = FhirContext.forR4();
    PrePopulatedValidationSupport ig = new PrePopulatedValidationSupport(ctx);
    int loaded = 0;
    if (args.length > 0 && !"-".equals(args[0])) {
      try (InputStream in = new FileInputStream(args[0])) {
        NpmPackage pkg = NpmPackage.fromPackage(in);
        for (String name : pkg.list("package")) {
          if (!name.endsWith(".json") || name.equals("package.json") || name.startsWith(".")) continue;
          String json;
          try (InputStream r = pkg.load("package", name)) { json = new String(r.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
          if (!(json.contains("\"resourceType\":\"StructureDefinition\"") || json.contains("\"resourceType\" : \"StructureDefinition\"")
              || json.contains("\"resourceType\":\"ValueSet\"") || json.contains("\"resourceType\" : \"ValueSet\"")
              || json.contains("\"resourceType\":\"CodeSystem\"") || json.contains("\"resourceType\" : \"CodeSystem\""))) continue;
          ig.addResource(ctx.newJsonParser().parseResource(json));
          loaded++;
        }
        System.out.println("IG " + pkg.name() + "#" + pkg.version() + " conformance resources loaded: " + loaded);
      }
    }
    ValidationSupportChain chain = new ValidationSupportChain(
        new DefaultProfileValidationSupport(ctx), ig, new SnapshotGeneratingValidationSupport(ctx),
        new InMemoryTerminologyServerValidationSupport(ctx), new CommonCodeSystemsTerminologyService(ctx));
    FhirValidator v = ctx.newValidator();
    v.registerValidatorModule(new FhirInstanceValidator(chain));
    String profile = loaded > 0 ? ",\"meta\":{\"profile\":[\"http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient\"]}" : "";
    String patient = "{\"resourceType\":\"Patient\"" + profile + ",\"gender\":\"unknown\",\"name\":[{\"family\":\"Test\"}]}";
    ValidationResult res = v.validateWithResult(patient);
    System.out.println("java " + Runtime.version() + " valid=" + res.isSuccessful() + " messages=" + res.getMessages().size());
    res.getMessages().stream().limit(8).forEach(m -> System.out.println("  " + m.getSeverity() + " " + m.getLocationString() + " " + m.getMessage()));
  }
}
