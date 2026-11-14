B='sangam-config/src/main/java/org/sangam/config/'
def sub(p,a,b):
    t=open(p,encoding='utf-8').read(); assert a in t,a; open(p,'w',encoding='utf-8',newline='\n').write(t.replace(a,b,1))
p=B+'master/ClassificationScheme.java'
sub(p,""" *       attributes: [chargedVoted]""",""" *       # optional per segment: hierarchy (parent-child validation), see ClassificationRegistry#validate:
 *       #   {path: [major, subMajor], separator: "-", noneCode: "00", openRuleFlag: minorRule,
 *       #    anyParentFlag: standard, templateWildcard: "*", templateChild: minor}
 *       attributes: [chargedVoted]""")
sub(p,"""                          String owner, String pattern) {
    }""","""                          String owner, String pattern, Map<String, Object> hierarchy) {
    }""")
sub(p,"""Tree.str(s.get("pattern"), null)));""","""Tree.str(s.get("pattern"), null),
                    Tree.map(s.get("hierarchy"))));""")
p=B+'master/MasterStore.java'
sub(p,"""    /** In-memory implementation. */""","""    /** Codes of a segment whose parent code starts with {@code parentPrefix}. */
    default List<MasterCode> children(String scope, String scheme, String segment, String parentPrefix) {
        return list(scope, scheme, segment).stream()
                .filter(c -> c.parentCode() != null && c.parentCode().startsWith(parentPrefix)).toList();
    }

    /** In-memory implementation. */""")
p=B+'store/JdbcConfigStore.java'
sub(p,"""    private static MasterCode code(java.sql.ResultSet rs)""","""    @Override
    public List<MasterCode> children(String scope, String scheme, String segment, String parentPrefix) {
        return inTx(c -> Jdbc.query(c, "SELECT * FROM CFG_MASTER_CODE WHERE scope_id = ? AND scheme_id = ? AND segment_id = ? "
                + "AND parent_code LIKE ?", JdbcConfigStore::code, scope, scheme, segment, parentPrefix + "%"));
    }

    private static MasterCode code(java.sql.ResultSet rs)""")
p=B+'api/ConfigConnector.java'
sub(p,"registry.validate(r.tenantId(), s, c, asOf)","""registry.validate(r.tenantId(), s, c, asOf, id -> p.list("classification.schemes").stream()
                            .filter(m -> id.equals(m.get("id"))).findFirst().map(ClassificationScheme::from).orElse(null))""")
p=B+'master/ClassificationRegistry.java'
sub(p,"""    public List<Finding> validate(String scope, ClassificationScheme s, Code c, LocalDate asOf) {
""","""    public List<Finding> validate(String scope, ClassificationScheme s, Code c, LocalDate asOf) {
        return validate(scope, s, c, asOf, id -> id.equals(s.id()) ? s : null);
    }

    /**
     * As above, with a scheme lookup: a segment that references another scheme ({@code scheme: HOA}) follows the
     * hierarchy declared on that scheme's segment of the same id, so a government's own layout inherits it.
     */
    public List<Finding> validate(String scope, ClassificationScheme s, Code c, LocalDate asOf,
                                  java.util.function.Function<String, ClassificationScheme> schemes) {
""")
sub(p,"""                String segId = seg.scheme() != null ? seg.id() : seg.id();
""","""                String segId = seg.id();
                ClassificationScheme ref = seg.scheme() == null ? s : schemes.apply(seg.scheme());
                ClassificationScheme.Segment def = ref == null ? null : ref.segment(segId);
                if (def != null && !def.hierarchy().isEmpty()) {
                    checkHierarchy(scope, scheme, def, c, v, asOf, out);
                    continue;
                }
""")
sub(p,"""    /** Looks a code up in the scope and its ancestors""","""    /**
     * Parent-child check of one segment, configured by {@code hierarchy}:
     * <ul>
     *   <li>{@code path}: ancestor segments; their values joined by {@code separator} form the parent key;</li>
     *   <li>{@code anyParentFlag}: a code with this flag and no parent is valid under any parent;</li>
     *   <li>{@code openRuleFlag}: an ancestor, or a sibling row, with this flag lets codes be opened by that rule;</li>
     *   <li>{@code templateWildcard}: rows whose parent is {@code <ancestor>-<wildcard>n} apply when the immediate parent
     *       segment was itself opened by rule;</li>
     *   <li>{@code noneCode}: valid when the parent has no coded children; {@code templateChild}: this segment's codes
     *       are opened by rule when that child segment has template rows under the parent.</li>
     * </ul>
     * A parent with no coded children and no rule cannot refute a code: it passes with a WARNING. A rule-opened code
     * passes with an INFO finding quoting the rule.
     */
    private void checkHierarchy(String scope, String scheme, ClassificationScheme.Segment def, Code c, String v, LocalDate asOf,
                                List<Finding> out) {
        Map<String, Object> h = def.hierarchy();
        List<String> path = Tree.strings(h.get("path"));
        String sep = Tree.str(h.get("separator"), "-");
        List<String> values = new ArrayList<>();
        for (String p : path) {
            if (c.segment(p) == null) {
                return; // reported as a missing or optional ancestor
            }
            values.add(c.segment(p));
        }
        String parent = String.join(sep, values);
        if (lookup(scope, scheme, def.id(), path.isEmpty() ? null : parent, v, asOf).isPresent()) {
            return;
        }
        String anyFlag = Tree.str(h.get("anyParentFlag"), null);
        if (anyFlag != null && lookup(scope, scheme, def.id(), null, v, asOf)
                .filter(m -> Tree.bool(m.flags().get(anyFlag), false)).isPresent()) {
            return;
        }
        if (v.equals(Tree.str(h.get("noneCode"), null)) && !path.isEmpty()
                && children(scope, scheme, def.id(), parent, asOf).isEmpty()) {
            return;
        }
        String wildcard = Tree.str(h.get("templateWildcard"), null);
        String template = wildcard == null || path.size() < 2 ? null
                : String.join(sep, values.subList(0, values.size() - 1)) + sep + wildcard;
        if (template != null && children(scope, scheme, def.id(), template, asOf).stream().anyMatch(m -> m.code().equals(v))) {
            return;
        }
        String childSeg = Tree.str(h.get("templateChild"), null);
        if (childSeg != null && wildcard != null && !children(scope, scheme, childSeg, parent + sep + wildcard, asOf).isEmpty()) {
            out.add(Finding.info("CODE_OPENED_BY_RULE", def.id(), "code " + v + " opened by rule under " + parent, null));
            return;
        }
        String ruleFlag = Tree.str(h.get("openRuleFlag"), null);
        if (ruleFlag != null) {
            String rule = null;
            for (int i = 0; i < path.size() && rule == null; i++) {
                String ancestorParent = i == 0 ? null : String.join(sep, values.subList(0, i));
                rule = lookup(scope, scheme, path.get(i), ancestorParent, values.get(i), asOf)
                        .map(m -> Tree.str(m.flags().get(ruleFlag), null)).orElse(null);
            }
            if (rule == null) {
                rule = children(scope, scheme, def.id(), parent, asOf).stream()
                        .filter(m -> parent.equals(m.parentCode()) && m.flags().get(ruleFlag) != null)
                        .map(m -> Tree.str(m.flags().get(ruleFlag), null)).findFirst().orElse(null);
            }
            if (rule != null) {
                out.add(Finding.info("CODE_OPENED_BY_RULE", def.id(), "code " + v + " under " + parent + ": " + rule, null));
                return;
            }
        }
        if (!path.isEmpty() && children(scope, scheme, def.id(), parent, asOf).isEmpty()
                && (template == null || children(scope, scheme, def.id(), template, asOf).isEmpty())) {
            out.add(Finding.warn("PARENT_HAS_NO_CODES", def.id(), "no codes listed under " + parent + "; " + v + " not verified", null));
            return;
        }
        out.add(Finding.error("CODE_NOT_UNDER_PARENT", def.id(), "code " + v + " is not valid under " + parent + " on " + asOf, null));
    }

    /** Codes valid on the date whose parent starts with the prefix, through the scope chain. */
    private List<MasterCode> children(String scope, String scheme, String segment, String prefix, LocalDate asOf) {
        List<String> scopes = new ArrayList<>(ScopeRef.parse(scope).prefixes());
        scopes.addAll(referenceScopes.apply(scope));
        scopes.add("*");
        List<MasterCode> out = new ArrayList<>();
        for (String sc : scopes) {
            store.children(sc, scheme, segment, prefix).stream().filter(m -> m.validOn(asOf)).forEach(out::add);
        }
        return out;
    }

    /** Looks a code up in the scope and its ancestors""")
p='sangam-packs/src/main/resources/profiles/in/IN.yaml'
sub(p,"""    - id: subMajor
      width: 2
      master: true
      owner: jurisdiction
    - id: minor
      width: 3
      master: true
      owner: jurisdiction
""","""    - id: subMajor
      width: 2
      master: true
      owner: jurisdiction
      hierarchy:
        path: [major]
        noneCode: '00'
        templateWildcard: '*'
        templateChild: minor
    - id: minor
      width: 3
      master: true
      owner: jurisdiction
      hierarchy:
        path: [major, subMajor]
        anyParentFlag: standard
        openRuleFlag: minorRule
        templateWildcard: '*'
""")
p='sangam-config/src/test/java/org/sangam/config/ProfileDataTest.java'
sub(p,'"in/seeds/hoa-major-heads.csv", "in/seeds/hoa-standard-minor-heads.csv",','"in/seeds/hoa-major-heads.csv", "in/seeds/hoa-sub-major-heads.csv", "in/seeds/hoa-minor-heads.csv",\n                "in/seeds/hoa-standard-minor-heads.csv",')
sub(p,'''        assertEquals("HOA_BR", br.str("classification.defaultScheme", null));
''','''        assertEquals("HOA_BR", br.str("classification.defaultScheme", null));
        // parent-child: a real minor under the wrong major is refused; standard heads, rules and templates pass
        LocalDate d = LocalDate.of(2026, 11, 5);
        assertTrue(reg.validate("IN", hoa, reg.parse(hoa, "2054-00-095-01-00-13"), d).isEmpty());
        assertTrue(hasCode(reg.validate("IN", hoa, reg.parse(hoa, "0005-00-095-01-00-13"), d), "CODE_NOT_UNDER_PARENT"), "wrong major");
        assertTrue(hasCode(reg.validate("IN", hoa, reg.parse(hoa, "0007-99-001-01-00-13"), d), "CODE_NOT_UNDER_PARENT"), "wrong sub-major");
        assertTrue(hasCode(reg.validate("IN", hoa, reg.parse(hoa, "0007-00-001-01-00-13"), d), "CODE_NOT_UNDER_PARENT"), "00 where sub-majors are coded");
        assertFalse(Finding.anyError(reg.validate("IN", hoa, reg.parse(hoa, "0404-00-555-01-00-13"), d)), "major-level minorRule");
        assertTrue(hasCode(reg.validate("IN", hoa, reg.parse(hoa, "0404-00-555-01-00-13"), d), "CODE_OPENED_BY_RULE"));
        assertFalse(Finding.anyError(reg.validate("IN", hoa, reg.parse(hoa, "0217-01-555-01-00-13"), d)), "sub-major minorRule");
        assertTrue(hasCode(reg.validate("IN", hoa, reg.parse(hoa, "0700-37-101-01-00-13"), d), "CODE_OPENED_BY_RULE"), "subMajorRule");
        assertFalse(Finding.anyError(reg.validate("IN", hoa, reg.parse(hoa, "0700-37-101-01-00-13"), d)));
        assertTrue(hasCode(reg.validate("IN", hoa, reg.parse(hoa, "0700-37-555-01-00-13"), d), "CODE_NOT_UNDER_PARENT"), "not in the template");
        // a State layout inherits the hierarchy of the scheme its segments reference
        java.util.function.Function<String, ClassificationScheme> lookup =
                id -> ClassificationScheme.from(ka.map("classification.schemes." + id));
        assertTrue(hasCode(reg.validate("KA", kas, reg.parse(kas, "0005-00-095-0-03-240"), d, lookup), "CODE_NOT_UNDER_PARENT"));
        assertFalse(Finding.anyError(reg.validate("KA", kas, reg.parse(kas, "2054-00-095-0-03-240"), d, lookup)));
''')
t=open(p,encoding='utf-8').read()
i=t.rindex('}')
t=t[:i]+'''
    private static boolean hasCode(List<Finding> f, String code) {
        return f.stream().anyMatch(x -> code.equals(x.code()));
    }
}
'''
open(p,'w',encoding='utf-8',newline='\n').write(t)
