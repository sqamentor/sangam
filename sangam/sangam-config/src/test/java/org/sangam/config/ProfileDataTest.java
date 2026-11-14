// SPDX-License-Identifier: Apache-2.0
package org.sangam.config;

import org.junit.jupiter.api.Test;
import org.sangam.config.catalogue.DocumentTypeCatalogue;
import org.sangam.config.common.Finding;
import org.sangam.config.master.ClassificationRegistry;
import org.sangam.config.master.ClassificationScheme;
import org.sangam.config.master.MasterStore;
import org.sangam.config.master.SeedLoader;
import org.sangam.config.profile.InMemoryProfileRepository;
import org.sangam.config.profile.LayerModel;
import org.sangam.config.profile.ProfileDocument;
import org.sangam.config.profile.ProfileLoader;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.config.profile.ResolvedProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Loads the shipped government profiles (sangam-packs resources) and resolves every chain: no ERROR findings. */
class ProfileDataTest {

    static final Path DIR = Path.of("..", "sangam-packs", "src", "main", "resources", "profiles");

    /**
     * SL-97: every money ledger (kind LIMIT or RECEIVABLE, LIMIT by default) of every jurisdiction-level profile and
     * below carries a currency equal to that profile's own {@code locale.currency}. The regime and international presets
     * are currency-neutral (layers.yaml requires locale.currency from the jurisdiction layer down), so the currency
     * comes from the jurisdiction. Without it the ledger wrote NULL-currency rows and the first posting failed
     * (IfmsPacksIT set-up, 2026-11-11: JdbcLedgerStore.currencyOf, NullPointerException).
     */
    @Test
    void everyMoneyLedgerHasItsProfileCurrency() throws Exception {
        List<ProfileDocument> docs = ProfileLoader.loadDirectory(DIR);
        LayerModel layers = LayerModel.from(ProfileLoader.parseTree(Files.readString(DIR.resolve("layers.yaml"))).get("layers"));
        ProfileResolver r = new ProfileResolver(InMemoryProfileRepository.active(docs), layers);
        int ledgers = 0;
        for (ProfileDocument d : docs) {
            if (d.layer().equals("international") || d.layer().equals("regime")) {
                continue;
            }
            ResolvedProfile p = r.resolve(d.id(), LocalDate.of(2026, 11, 5));
            String ccy = p.str("locale.currency", null);
            for (java.util.Map<String, Object> l : p.list("ledgers")) {
                String kind = String.valueOf(l.getOrDefault("kind", "LIMIT")).toUpperCase(java.util.Locale.ROOT);
                if (!kind.equals("LIMIT") && !kind.equals("RECEIVABLE")) {
                    continue;
                }
                ledgers++;
                assertTrue(l.get("currency") != null, d.id() + ": money ledger " + l.get("id") + " has no currency");
                assertTrue(ccy != null, d.id() + ": money ledgers but no locale.currency");
                assertEquals(ccy, l.get("currency"), d.id() + ": ledger " + l.get("id") + " currency vs locale.currency");
            }
        }
        assertTrue(ledgers >= 37 + 10,"the Indian governments and departments resolve their BUDGET ledger: " + ledgers);
    }

    @Test
    void everyGovernmentAndDepartmentChainResolvesWithoutErrors() throws Exception {
        List<ProfileDocument> docs = ProfileLoader.loadDirectory(DIR);
        LayerModel layers = LayerModel.from(ProfileLoader.parseTree(Files.readString(DIR.resolve("layers.yaml"))).get("layers"));
        ProfileResolver r = new ProfileResolver(InMemoryProfileRepository.active(docs), layers);
        int n = 0;
        for (ProfileDocument d : docs) {
            if (d.layer().equals("government") || d.layer().equals("department")) {
                ResolvedProfile p = r.resolve(d.id(), LocalDate.of(2026, 11, 5));
                List<Finding> errors = Finding.errors(p.findings());
                assertTrue(errors.isEmpty(), d.id() + ": " + errors);
                assertEquals("INR", p.str("locale.currency", null), d.id());
                assertTrue(DocumentTypeCatalogue.of(p).lint().stream().noneMatch(Finding::isError), d.id());
                n++;
            }
        }
        assertEquals(37 + 10, n); // GOI + 36 States / UTs, 10 GOI department profiles (decision 257 adds GOI.DEFENCE.DRDO)
        // every government has the complete delegation structure (financial powers + procurement mode tables)
        javax.xml.parsers.DocumentBuilderFactory xml = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        xml.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        int govs = 0;
        for (ProfileDocument d : docs) {
            if (d.layer().equals("government")) {
                ResolvedProfile p = r.resolve(d.id(), LocalDate.of(2026, 11, 5));
                List<java.util.Map<String, Object>> tables = p.list("delegation.tables");
                assertEquals(2, tables.size(), d.id());
                for (java.util.Map<String, Object> t : tables) {
                    xml.newDocumentBuilder().parse(DIR.resolve(t.get("file").toString()).toFile());
                }
                govs++;
            }
        }
        assertEquals(37, govs);
        // the seven international country profiles (INTERNATIONAL-PFM section 5) resolve on their regime presets
        java.util.Map<String, String[]> countries = java.util.Map.of("USA", new String[]{"USD", "10"}, "GBR", new String[]{"GBP", "4"},
                "BRA", new String[]{"BRL", "1"}, "KEN", new String[]{"KES", "7"}, "IDN", new String[]{"IDR", "1"},
                "PHL", new String[]{"PHP", "1"}, "BGD", new String[]{"BDT", "7"});
        for (var e : countries.entrySet()) {
            ResolvedProfile p = r.resolve(e.getKey(), LocalDate.of(2026, 11, 5));
            assertTrue(Finding.errors(p.findings()).isEmpty(), e.getKey() + ": " + Finding.errors(p.findings()));
            assertEquals(e.getValue()[0], p.str("locale.currency", null));
            assertEquals(Integer.parseInt(e.getValue()[1]), p.locale().fiscal().startMonth());
            assertEquals(7, DocumentTypeCatalogue.of(p).all().stream().filter(t -> "PFM".equals(t.family())).count() >= 7 ? 7 : -1);
            assertTrue(DocumentTypeCatalogue.of(p).chain("PFM-CH-3").orElseThrow().liableRoles().contains("CERTIFYING_OFFICER"));
        }
        assertEquals(1, DocumentTypeCatalogue.of(r.resolve("KEN", LocalDate.of(2026, 11, 5))).chain("KEN-EXCHEQUER").orElseThrow()
                .externalSteps().size());
        assertEquals(java.util.List.of("ESTIMATE", "DEPARTMENTAL_BUDGET", "RELEASE"),
                r.resolve("GBR", LocalDate.of(2026, 11, 5)).list("ledgers").get(0).get("stages"));
        assertTrue(SeedLoader.csv("IN", Files.readString(DIR.resolve("in/seeds/hoa-major-heads.csv"))).size() > 300);
        ResolvedProfile hr = r.resolve("HR", LocalDate.of(2026, 11, 5));
        assertFalse(hr.list("rulebooks.own").isEmpty(), "Haryana adopts the Punjab rulebooks");
        ResolvedProfile rail = r.resolve("GOI.RAILWAYS", LocalDate.of(2026, 11, 5));
        assertEquals("RAILWAY_BOARD_SOP", rail.str("regime.delegationBook", null));
        // decision 257: the Ministry of Defence is not a scientific department; DRDO, its listed part, is its own
        // department layer below it (chain GOI > GOI.DEFENCE > GOI.DEFENCE.DRDO) and is; neither value is a placeholder
        ResolvedProfile defence = r.resolve("GOI.DEFENCE", LocalDate.of(2026, 11, 5));
        assertEquals(Boolean.FALSE, defence.get("procurement.scientificDepartment"));
        assertFalse(defence.isPlaceholder("procurement.scientificDepartment"));
        ResolvedProfile drdo = r.resolve("GOI.DEFENCE.DRDO", LocalDate.of(2026, 11, 5));
        assertTrue(Finding.errors(drdo.findings()).isEmpty(), "GOI.DEFENCE.DRDO: " + Finding.errors(drdo.findings()));
        assertEquals(Boolean.TRUE, drdo.get("procurement.scientificDepartment"));
        assertFalse(drdo.isPlaceholder("procurement.scientificDepartment"));
        assertEquals("DFPDS_2021", drdo.str("regime.delegationBook", null), "DRDO inherits the Defence layer");
        assertEquals(List.of("GOI", "GOI.DEFENCE", "GOI.DEFENCE.DRDO"),
                drdo.chain().stream().map(c -> c.split("@", 2)[0]).filter(c -> c.startsWith("GOI")).toList());
        // SL-102: the 100 crore band is typed money (integer paise + the profile currency), no longer the text "100CR"
        assertEquals(org.sangam.core.money.Money.ofMinor(100_000_000_000L, "INR"),
                r.resolve("GOI", LocalDate.of(2026, 11, 5)).money("appraisal.ladder.0.upTo"));
    }

    @Test
    void headOfAccountSeedValidatesFifteenDigitCodes() throws Exception {
        List<ProfileDocument> docs = ProfileLoader.loadDirectory(DIR);
        ProfileResolver r = new ProfileResolver(InMemoryProfileRepository.active(docs), LayerModel.open());
        ResolvedProfile goi = r.resolve("GOI", LocalDate.of(2026, 11, 5));
        MasterStore store = new MasterStore.InMemory();
        for (String f : List.of("in/seeds/hoa-major-heads.csv", "in/seeds/hoa-sub-major-heads.csv", "in/seeds/hoa-minor-heads.csv",
                "in/seeds/hoa-standard-minor-heads.csv", "in/seeds/object-heads.csv")) {
            SeedLoader.load(store, SeedLoader.csv("IN", Files.readString(DIR.resolve(f))));
        }
        ClassificationRegistry reg = new ClassificationRegistry(store).withReferenceScopes(s -> List.of("IN"));
        ClassificationScheme hoa = ClassificationScheme.from(goi.map("classification.schemes.HOA"));
        ClassificationRegistry.Code c = reg.parse(hoa, "2054-00-001-01-00-13");
        List<Finding> f = reg.validate("IN", hoa, c, LocalDate.of(2026, 11, 5));
        assertTrue(f.isEmpty(), f.toString());
        assertEquals("REVENUE", c.attributes().get("section"));
        assertEquals("205400001010013", c.compact());
        ClassificationRegistry.Code bad = reg.parse(hoa, "9999-00-001-01-00-13");
        assertTrue(Finding.anyError(reg.validate("IN", hoa, bad, LocalDate.of(2026, 11, 5))));
        // per-state segment structures (IFMS-STATES 9.2): Karnataka SH1-DH2-OH3, Bihar 19 digits + V/C flag
        ResolvedProfile ka = r.resolve("KA", LocalDate.of(2026, 11, 5));
        ClassificationScheme kas = ClassificationScheme.from(ka.map("classification.schemes.HOA_KA"));
        List<Finding> kf = reg.validate("KA", kas, reg.parse(kas, "2055-00-001-0-03-240"), LocalDate.of(2026, 11, 5));
        assertTrue(kf.isEmpty(), kf.toString());
        ResolvedProfile br = r.resolve("BR", LocalDate.of(2026, 11, 5));
        ClassificationScheme brs = ClassificationScheme.from(br.map("classification.schemes.HOA_BR"));
        List<Finding> bf = reg.validate("BR", brs, reg.parse(brs, "12-2054-00-001-0001-01-01-V"), LocalDate.of(2026, 11, 5));
        assertTrue(bf.isEmpty(), bf.toString());
        assertEquals("HOA_BR", br.str("classification.defaultScheme", null));
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
    }

    /** SL-15: the full official UN COFOG 1999 (UNSD) is loaded, verified, and class to group to division is consistent. */
    @Test
    void cofogSeedIsCompleteVerifiedAndHierarchical() throws Exception {
        List<ProfileDocument> docs = ProfileLoader.loadDirectory(DIR);
        ProfileResolver r = new ProfileResolver(InMemoryProfileRepository.active(docs), LayerModel.open());
        LocalDate d = LocalDate.of(2026, 11, 5);
        for (String id : List.of("INTL", "GOI", "RJ")) {
            assertFalse(r.resolve(id, d).placeholders().containsKey("classification.schemes.COFOG"), id);
        }
        // GA-55 is an employee-wise yearly report template, not a code-list placeholder; its official form stays [U].
        // APPROVE-401 step 2b: a report is not a bill type, so it is a formTemplates row, outside the bill catalogue
        ResolvedProfile rj = r.resolve("RJ", d);
        assertTrue(rj.placeholders().keySet().stream().noneMatch(k -> k.contains("GA-55")), rj.placeholders().toString());
        assertTrue(DocumentTypeCatalogue.of(rj).byReference("RJ_GA_55").isEmpty(), "the GA-55 report is no bill type");
        var ga55 = rj.list("formTemplates").stream().filter(t -> "RJ:GA 55".equals(t.get("formNumber"))).findFirst().orElseThrow();
        assertEquals("RJ_GA_55", ga55.get("id"));
        assertEquals("REPORT", ga55.get("formKind"));
        assertEquals("U", ((java.util.Map<?, ?>) ((java.util.Map<?, ?>) ga55.get("evidence")).get("fieldLayout")).get("status"));
        assertEquals(List.of("employee", "year", "month"), ((java.util.Map<?, ?>) ga55.get("report")).get("parameters"));
        ResolvedProfile intl = r.resolve("INTL", d);
        List<org.sangam.config.master.MasterCode> rows =
                SeedLoader.csv("INTL", Files.readString(DIR.resolve("intl/seeds/cofog.csv")));
        java.util.Map<String, Long> bySeg = rows.stream().collect(java.util.stream.Collectors.groupingBy(
                org.sangam.config.master.MasterCode::segment, java.util.stream.Collectors.counting()));
        assertEquals(java.util.Map.of("division", 10L, "group", 69L, "class", 109L), bySeg);
        java.util.Set<String> full = new java.util.HashSet<>();
        for (var m : rows) {
            assertEquals("V", m.flags().get("evidence"), m.code());
            assertTrue(m.sourceRef().contains("5368887bb253a1819bfefc6a9eedc74cb76f50c6a7c35133d2420b1d4a3b39b5"), m.code());
            assertFalse(m.name().containsKey("hi"), "no Hindi without an official source");
            full.add(m.parentCode() == null ? m.code() : m.parentCode() + "." + m.code());
        }
        for (var m : rows) {
            if (m.parentCode() != null) {
                assertTrue(full.contains(m.parentCode()), "parent of " + m.parentCode() + "." + m.code());
                assertEquals(m.segment().equals("group") ? 1 : 2, m.parentCode().split("\\.").length, m.code());
            }
        }
        assertTrue(full.containsAll(List.of("01", "07.3.1", "10.9.0", "04.7.4")));
        MasterStore store = new MasterStore.InMemory();
        SeedLoader.load(store, rows);
        ClassificationRegistry reg = new ClassificationRegistry(store);
        ClassificationScheme cofog = ClassificationScheme.from(intl.map("classification.schemes.COFOG"));
        for (String ok : List.of("01", "07.3", "07.3.1", "10.9.0", "0731")) {
            List<Finding> f = reg.validate("INTL", cofog, reg.parse(cofog, ok), d);
            assertTrue(f.isEmpty(), ok + ": " + f);
        }
        assertTrue(hasCode(reg.validate("INTL", cofog, reg.parse(cofog, "11"), d), "CODE_UNKNOWN"), "no division 11");
        assertTrue(hasCode(reg.validate("INTL", cofog, reg.parse(cofog, "02.6"), d), "CODE_NOT_UNDER_PARENT"), "defence has 5 groups");
        assertTrue(hasCode(reg.validate("INTL", cofog, reg.parse(cofog, "07.3.5"), d), "CODE_NOT_UNDER_PARENT"), "class under wrong group");
    }

    private static boolean hasCode(List<Finding> f, String code) {
        return f.stream().anyMatch(x -> code.equals(x.code()));
    }

    /**
     * SL-125 (owner 2026-11-12, fail closed core-wide): the US health revenue-cycle reference has no fiscal year (the
     * tenant sets it). It resolves without errors on the shipped layer model (locale.fiscal is no longer a layer
     * requirement), with a FISCAL_NOT_CONFIGURED warning, and a fiscal operation is refused naming US and locale.fiscal;
     * every other shipped jurisdiction, government and department chain carries a complete fiscal calendar.
     */
    @Test
    void usHasNoFiscalYearAndRefusesFiscalOperationsWhileEveryOtherChainHasOne() throws Exception {
        List<ProfileDocument> docs = ProfileLoader.loadDirectory(DIR);
        LayerModel layers = LayerModel.from(ProfileLoader.parseTree(Files.readString(DIR.resolve("layers.yaml"))).get("layers"));
        ProfileResolver r = new ProfileResolver(InMemoryProfileRepository.active(docs), layers);
        ResolvedProfile us = r.resolve("US", LocalDate.of(2026, 11, 12));
        assertTrue(Finding.errors(us.findings()).isEmpty(), "US: " + Finding.errors(us.findings()));
        assertTrue(us.findings().stream().anyMatch(f -> f.severity() == Finding.Severity.WARNING
                && f.code().equals(ProfileResolver.FISCAL_NOT_CONFIGURED)), us.findings()::toString);
        assertFalse(us.locale().hasFiscal());
        org.sangam.config.locale.FiscalYearNotConfiguredException e = org.junit.jupiter.api.Assertions.assertThrows(
                org.sangam.config.locale.FiscalYearNotConfiguredException.class, () -> us.locale().fiscal());
        assertTrue(e.getMessage().contains("US") && e.getMessage().contains("locale.fiscal"), e.getMessage());
        assertEquals("USD", us.locale().money().currency().getCurrencyCode());
        int withFiscal = 0;
        for (ProfileDocument d : docs) {
            if (d.id().equals("US") || !List.of("jurisdiction", "government", "department").contains(d.layer())) {
                continue;
            }
            ResolvedProfile p = r.resolve(d.id(), LocalDate.of(2026, 11, 12));
            assertTrue(p.locale().hasFiscal(), d.id() + ": " + p.locale().fiscalProblem());
            assertTrue(p.findings().stream().noneMatch(f -> f.code().startsWith("FISCAL_")), d.id() + ": " + p.findings());
            withFiscal++;
        }
        assertEquals(1 + 7 + 37 + 10, withFiscal); // IN, the seven INTL countries, GOI + 36 States / UTs, 10 GOI departments
    }

    /**
     * Decisions 244 and 257, coordinator round 6: the GOI base and every State and UT declare
     * {@code procurement.scientificDepartment} as a [U] placeholder with no value. Validation keeps the
     * PLACEHOLDER_NO_VALUE warning (a warning, never an error), and at run time the config connector answers
     * {@code profile.get} with no value and the path listed as a placeholder, which the procurement packs refuse with
     * DEPARTMENT_CLASS_NOT_CONFIRMED (sangam-packs DepartmentClassLookupTest, the same answer shape). Defence answers
     * false and DRDO true, neither a placeholder.
     */
    @Test
    void anUndeclaredScientificClassWarnsAndIsAnsweredWithoutAValueWhileDefenceAndDrdoAnswer() throws Exception {
        String path = "procurement.scientificDepartment";
        List<ProfileDocument> docs = ProfileLoader.loadDirectory(DIR);
        LayerModel layers = LayerModel.from(ProfileLoader.parseTree(Files.readString(DIR.resolve("layers.yaml"))).get("layers"));
        ProfileResolver r = new ProfileResolver(InMemoryProfileRepository.active(docs), layers);
        org.sangam.config.profile.ProfileValidator validator = new org.sangam.config.profile.ProfileValidator(layers);
        org.sangam.config.api.ConfigConnector connector = new org.sangam.config.api.ConfigConnector(r,
                new ClassificationRegistry(new MasterStore.InMemory()), new org.sangam.config.catalogue.NumberSeries.InMemory());
        int undeclared = 0;
        for (ProfileDocument d : docs) {
            if (!d.layer().equals("government")) {
                continue;
            }
            List<Finding> findings = validator.validateDocument(d);
            assertTrue(Finding.errors(findings).isEmpty(), d.id() + ": " + Finding.errors(findings));
            assertTrue(findings.stream().anyMatch(f -> f.severity() == Finding.Severity.WARNING
                    && "PLACEHOLDER_NO_VALUE".equals(f.code()) && path.equals(f.path())), d.id() + ": " + findings);
            java.util.Map<String, Object> out = profileGet(connector, d.id(), path);
            assertTrue(((java.util.Map<?, ?>) out.get("values")).containsKey(path), d.id());
            assertEquals(null, ((java.util.Map<?, ?>) out.get("values")).get(path), d.id() + ": no value is invented");
            assertTrue(((List<?>) out.get("placeholders")).contains(path), d.id() + ": " + out.get("placeholders"));
            undeclared++;
        }
        assertEquals(37, undeclared, "GOI + 36 States / UTs");
        assertTrue(docs.stream().anyMatch(d -> d.id().equals("AP")), "State AP is among them");
        for (String id : List.of("GOI.DEFENCE", "GOI.DEFENCE.DRDO")) {
            ProfileDocument d = docs.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
            assertTrue(validator.validateDocument(d).stream().noneMatch(f -> "PLACEHOLDER_NO_VALUE".equals(f.code())
                    && path.equals(f.path())), id);
            java.util.Map<String, Object> out = profileGet(connector, id, path);
            assertEquals(id.equals("GOI.DEFENCE.DRDO") ? Boolean.TRUE : Boolean.FALSE,
                    ((java.util.Map<?, ?>) out.get("values")).get(path), id);
            assertFalse(((List<?>) out.get("placeholders")).contains(path), id + ": " + out.get("placeholders"));
        }
    }

    private static java.util.Map<String, Object> profileGet(org.sangam.config.api.ConfigConnector c, String tenant,
                                                            String path) {
        java.util.Map<String, Object> in = new java.util.LinkedHashMap<>();
        in.put("paths", path);
        in.put("asOf", "2026-11-05");
        org.sangam.core.integration.ConnectorResult res = c.invoke(new org.sangam.core.integration.ConnectorRequest(tenant,
                java.util.UUID.randomUUID(), "a", 1, null, null, "profile.get", in, null));
        return org.junit.jupiter.api.Assertions.assertInstanceOf(org.sangam.core.integration.ConnectorResult.Success.class,
                res, tenant).output();
    }
}
