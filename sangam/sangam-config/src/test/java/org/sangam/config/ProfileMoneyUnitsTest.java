// SPDX-License-Identifier: Apache-2.0
package org.sangam.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.sangam.config.catalogue.DocumentTypeCatalogue;
import org.sangam.config.profile.InMemoryProfileRepository;
import org.sangam.config.profile.LayerModel;
import org.sangam.config.profile.ProfileDocument;
import org.sangam.config.profile.ProfileLoader;
import org.sangam.config.profile.ProfileResolver;

/**
 * SD-398 money contract: a pack sends {@code amount} to {@code config.chain.signatures} in integer minor units (paise),
 * so the shipped profile bands are minor units too (profilegen, money.py). CAM 2024 2.2.9 note: two DSCs at Rs 10
 * lakh and above, i.e. at 10,00,00,000 paise. With the old rupee band (1000000) a bill of Rs 9,99,999.99 (99999999
 * paise) wrongly needed two signatures.
 */
class ProfileMoneyUnitsTest {

    static final Path DIR = Path.of("..", "sangam-packs", "src", "main", "resources", "profiles");

    @Test
    void signatureBandIsInPaise() throws Exception {
        List<ProfileDocument> docs = ProfileLoader.loadDirectory(DIR);
        LayerModel layers = LayerModel.from(ProfileLoader.parseTree(Files.readString(DIR.resolve("layers.yaml"))).get("layers"));
        ProfileResolver r = new ProfileResolver(InMemoryProfileRepository.active(docs), layers);
        var chain = DocumentTypeCatalogue.of(r.resolve("GOI", LocalDate.of(2026, 11, 5))).chain("PAO_3").orElseThrow();
        assertEquals(1, chain.signaturesFor(BigDecimal.valueOf(99_999_999L)), "one paisa below 10 lakh");
        assertEquals(2, chain.signaturesFor(BigDecimal.valueOf(100_000_000L)), "10 lakh");
    }
}
