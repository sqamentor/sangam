// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.scim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sangam.security.scim.ScimFilter.Attribute;
import org.sangam.security.scim.ScimFilter.Clause;
import org.sangam.security.scim.ScimFilter.Op;

/** M-099: the SCIM list filter subset of the contract (eq, co, sw on userName, externalId, displayName; and). Written, not run. */
class ScimFilterTest {

    @Test
    void comparisonsAndTheirJoinAreParsedCaseInsensitively() {
        assertEquals(List.of(new Clause(Attribute.USER_NAME, Op.EQ, "alice@example.org")),
                ScimFilter.parse("userName eq \"alice@example.org\""));
        assertEquals(List.of(new Clause(Attribute.EXTERNAL_ID, Op.SW, "ab"), new Clause(Attribute.DISPLAY_NAME, Op.CO, "Ra")),
                ScimFilter.parse("EXTERNALID SW \"ab\" AND displayname co \"Ra\""));
        assertEquals(List.of(new Clause(Attribute.USER_NAME, Op.EQ, "x")),
                ScimFilter.parse("urn:ietf:params:scim:schemas:core:2.0:User:userName eq \"x\""), "core URN prefix");
        assertEquals(List.of(new Clause(Attribute.ID, Op.EQ, "0190a1b2-c3d4")), ScimFilter.parse("id eq \"0190a1b2-c3d4\""),
                "the opaque SCIM id (decision 393 (a))");
        assertEquals(List.of(), ScimFilter.parse("  "));
        assertEquals(List.of(), ScimFilter.parse(null));
    }

    @Test
    void jsonEscapesInTheValueAreDecoded() {
        assertEquals("a\"b\\c/é", ScimFilter.parse("displayName eq \"a\\\"b\\\\c\\/\\u00e9\"").get(0).value());
    }

    @Test
    void everythingOutsideTheSubsetIsInvalidFilterNotIgnored() {
        for (String f : List.of("userName eq \"a\" or userName eq \"b\"", "not (userName eq \"a\")",
                "emails[type eq \"work\"]", "userName pr", "userName gt \"a\"", "title eq \"x\"", "userName eq 5",
                "userName eq \"open", "userName eq \"a\" and", "(userName eq \"a\")", "userName eq \"\\q\"")) {
            ScimException e = assertThrows(ScimException.class, () -> ScimFilter.parse(f), f);
            assertEquals(400, e.status(), f);
            assertEquals("invalidFilter", e.scimType(), f);
        }
        assertThrows(ScimException.class, () -> ScimFilter.parse("userName eq \"" + "a".repeat(ScimFilter.MAX_LENGTH) + "\""));
    }

    @Test
    void likePatternsEscapeWildcardsWithTheBangCharacter() {
        assertEquals("ab!%c!_d!!%", ScimFilter.likePattern(Op.SW, "ab%c_d!", false));
        assertEquals("%x[y%", ScimFilter.likePattern(Op.CO, "x[y", false), "[ is literal outside SQL Server; Oracle refuses !-[");
        assertEquals("%x![y%", ScimFilter.likePattern(Op.CO, "x[y", true), "SQL Server: [ opens a character class");
        assertTrue(ScimFilter.likePattern(Op.SW, "a", false).endsWith("%"));
    }
}
