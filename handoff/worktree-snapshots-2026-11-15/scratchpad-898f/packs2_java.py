import sys, pathlib
root = pathlib.Path(sys.argv[1])


def sub(rel, old, new, count=1):
    p = root / rel
    t = p.read_text(encoding='utf-8')
    if new in t:
        return
    assert t.count(old) == count, (rel, old[:80], t.count(old))
    p.write_text(t.replace(old, new), encoding='utf-8', newline='')


PD = 'sangam-packs/src/main/java/org/sangam/packs/spi/PackDescriptor.java'
sub(PD, ''' *                        pack configuration, for example {@code works-config})
 */''', ''' *                        pack configuration, for example {@code works-config})
 * @param placeholders   values shipped in the pack's DMN that are not from a verified source; the readiness check
 *                        blocks go-live of a tenant that enables the pack until it overrides the named decision
 */''')
sub(PD, '''                             Map<String, List<String>> tenantOverrides) {

    public PackDescriptor {''', '''                             Map<String, List<String>> tenantOverrides, List<Placeholder> placeholders) {

    /** Descriptor without placeholders. */
    public PackDescriptor(String id, String version, Map<String, String> name, String domain, String description,
                          List<String> mainProcesses, List<PackResource> resources, List<SeedRole> roles,
                          List<SeedSeat> seats, List<ConnectorUse> connectors, List<String> covers,
                          List<AcceptanceScenario> acceptance, List<String> dependsOn,
                          Map<String, List<String>> tenantOverrides) {
        this(id, version, name, domain, description, mainProcesses, resources, roles, seats, connectors, covers,
                acceptance, dependsOn, tenantOverrides, List.of());
    }

    public PackDescriptor {''')
sub(PD, '''        tenantOverrides = tenantOverrides == null ? Map.of() : Map.copyOf(tenantOverrides);
    }
''', '''        tenantOverrides = tenantOverrides == null ? Map.of() : Map.copyOf(tenantOverrides);
        placeholders = placeholders == null ? List.of() : List.copyOf(placeholders);
    }

    /**
     * A pack DMN value that needs owner confirmation (never an invented amount presented as verified).
     *
     * @param decision decision key a tenant overrides with the confirmed value
     * @param rule     which rule / value is unconfirmed
     * @param reason   why, and from which source the value must be confirmed
     */
    public record Placeholder(String decision, String rule, String reason) {
    }
''')

MP = 'sangam-packs/src/main/java/org/sangam/packs/spi/ManifestPack.java'
sub(MP, '''                    strings(m.path("dependsOn")), overrides(m.path("tenantOverrides")));''',
    '''                    strings(m.path("dependsOn")), overrides(m.path("tenantOverrides")), placeholders(m.path("placeholders")));''')

RC = 'sangam-onboarding/src/main/java/org/sangam/onboarding/readiness/ReadinessChecker.java'
sub(RC, '''        section(checks, "placeholders", out, placeholders(profile, packs));
''', '''        section(checks, "placeholders", out, placeholders(profile, packs));
        section(checks, "pack-placeholders", out, packPlaceholders(req.dmnOverrides(), enabled));
''')
sub(RC, '''    private static List<Problem> placeholders(ResolvedProfile p, PackDependencyResolver.Resolution packs) {''',
    '''    /** Unconfirmed pack DMN values: an error until the tenant overrides the decision with the confirmed table. */
    static List<Problem> packPlaceholders(Map<String, String> dmnOverrides, List<SangamPack> enabled) {
        List<Problem> out = new ArrayList<>();
        for (SangamPack pack : enabled) {
            for (var ph : pack.descriptor().placeholders()) {
                if (dmnOverrides == null || !dmnOverrides.containsKey(ph.decision())) {
                    out.add(Problem.error(StepId.READINESS, "PACK_PLACEHOLDER_UNCONFIRMED", pack.descriptor().id() + "/"
                            + ph.decision(), ph.rule() + ": " + ph.reason()
                            + "; supply the confirmed table in dmnOverrides[" + ph.decision() + "]"));
                }
            }
        }
        return out;
    }

    private static List<Problem> placeholders(ResolvedProfile p, PackDependencyResolver.Resolution packs) {''')
print('ok')
