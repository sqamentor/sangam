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

import java.math.BigDecimal;
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
        assertEquals(37 + 9, n); // GOI + 36 States / UTs, 9 GOI department profiles
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
        assertTrue(SeedLoader.csv("IN", Files.readString(DIR.resolve("in/seeds/hoa-major-heads.csv"))).size() > 300);
        ResolvedProfile hr = r.resolve("HR", LocalDate.of(2026, 11, 5));
        assertFalse(hr.list("rulebooks.own").isEmpty(), "Haryana adopts the Punjab rulebooks");
        ResolvedProfile rail = r.resolve("GOI.RAILWAYS", LocalDate.of(2026, 11, 5));
        assertEquals("RAILWAY_BOARD_SOP", rail.str("regime.delegationBook", null));
        assertEquals(new BigDecimal("1000000000"), r.resolve("GOI", LocalDate.of(2026, 11, 5)).amount("appraisal.ladder.0.upTo", null));
    }

    @Test
    void headOfAccountSeedValidatesFifteenDigitCodes() throws Exception {
        List<ProfileDocument> docs = ProfileLoader.loadDirectory(DIR);
        ProfileResolver r = new ProfileResolver(InMemoryProfileRepository.active(docs), LayerModel.open());
        ResolvedProfile goi = r.resolve("GOI", LocalDate.of(2026, 11, 5));
        MasterStore store = new MasterStore.InMemory();
        for (String f : List.of("in/seeds/hoa-major-heads.csv", "in/seeds/hoa-standard-minor-heads.csv", "in/seeds/object-heads.csv")) {
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
    }
}
