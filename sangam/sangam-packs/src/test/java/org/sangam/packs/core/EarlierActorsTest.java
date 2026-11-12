// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;

import org.sangam.packs.core.SodGuardListener.EarlierActor;

/**
 * SL-32 residual, J-P1-E09 (IFMS R-102 v0.1): the seats of a joint approval leave out every earlier actor of the case,
 * with the same earlier-actor filter the segregation-of-duties check uses, so a joint step is never offered to the
 * seat (or a seat of the person) that sanctioned the bill. Pure rule, no engine: {@link SodGuardListener#earlierActors}
 * and {@link SeatResolutionDelegate#excludingActors}.
 */
class EarlierActorsTest {

    static final String SANCTION = "sn_sanction#p1-competent-authority";
    static final List<String> POOL = List.of("SE-CIRCLE", "CE-ZONE");

    /** p1 seed holdings: p1.se holds SE-CIRCLE and, by additional charge, EE-DIV1; p1.ce holds CE-ZONE. */
    static final BiPredicate<String, String> HOLDS = (person, seat) -> Map.of(
            "p1.se", Set.of("SE-CIRCLE", "EE-DIV1"), "p1.ce", Set.of("CE-ZONE"), "p1.ao2", Set.of("AO-2"),
            "p1.clerk", Set.of("CLERK-DDO")).getOrDefault(person, Set.of()).contains(seat);

    static Map<String, Object> entry(String step, String person, String seat) {
        return Map.of("step", step, "person", person, "seat", seat);
    }

    /** The p1 actors before the joint step of a bill above 25,00,000: clerk, checker AO-2, sanction by CE-ZONE. */
    static List<Map<String, Object>> p1Log(String sanctionPerson, String sanctionSeat) {
        return List.of(entry("sc_scrutiny", "p1.ao2", "AO-2"), entry(SANCTION, sanctionPerson, sanctionSeat));
    }

    static List<String> jointSeats(List<String> pool, Set<String> only, List<Map<String, Object>> log) {
        return SeatResolutionDelegate.excludingActors(pool,
                SodGuardListener.earlierActors(only, "p1_joint", "p1.clerk", "CLERK-DDO", log), HOLDS);
    }

    @Test
    void theJointStepNeverLandsOnTheSanctioningSeat() {
        assertEquals(List.of("SE-CIRCLE"), jointSeats(POOL, Set.of(), p1Log("p1.ce", "CE-ZONE")));
        // the configured order does not matter: the sanctioning seat is left out wherever it stands
        assertEquals(List.of("SE-CIRCLE"), jointSeats(List.of("CE-ZONE", "SE-CIRCLE"), Set.of(),
                p1Log("p1.ce", "CE-ZONE")));
    }

    @Test
    void aSeatHeldByTheSanctioningPersonIsLeftOutToo() {
        // p1.se sanctions as EE-DIV1 (additional charge): SE-CIRCLE is also p1.se's, so it cannot be a second approval
        assertEquals(List.of("CE-ZONE"), jointSeats(POOL, Set.of(), p1Log("p1.se", "EE-DIV1")));
    }

    @Test
    void whenNoJointSeatActedEveryJointSeatApproves() {
        assertEquals(POOL, jointSeats(POOL, Set.of(), p1Log("p1.ee2", "EE-DIV1")));
    }

    @Test
    void theInitiatorAndTheCheckerAreEarlierActorsLikeTheSanctioner() {
        assertEquals(List.of("SE-CIRCLE"), jointSeats(List.of("CLERK-DDO", "AO-2", "SE-CIRCLE"), Set.of(),
                p1Log("p1.ce", "CE-ZONE")));
    }

    @Test
    void anEarlierRoundOfTheSameJointStepIsTheSameRole() {
        // after APPROVAL_INVALIDATED the case comes back through sanction and joint approval: SE-CIRCLE's first joint
        // approval is the same step (SoD skips it too), so SE-CIRCLE is asked again, not dropped
        List<Map<String, Object>> log = new java.util.ArrayList<>(p1Log("p1.ce", "CE-ZONE"));
        log.add(entry("p1_joint", "p1.se", "SE-CIRCLE"));
        assertEquals(List.of("SE-CIRCLE"), jointSeats(POOL, Set.of(), log));
    }

    @Test
    void theStepsFilterIsTheSodFilter() {
        // a task whose SoD compares only with the sanction: the initiator and the checker are not earlier actors
        List<EarlierActor> only = SodGuardListener.earlierActors(Set.of("sn_sanction"), "p1_joint", "p1.clerk",
                "CLERK-DDO", p1Log("p1.ce", "CE-ZONE"));
        assertEquals(List.of(new EarlierActor(SANCTION, "p1.ce", "CE-ZONE")), only,
                "a step matches by its task key before '#'");
        assertEquals(List.of("AO-2", "SE-CIRCLE"), jointSeats(List.of("AO-2", "CE-ZONE", "SE-CIRCLE"),
                Set.of("sn_sanction"), p1Log("p1.ce", "CE-ZONE")));
    }

    @Test
    void earlierActorsStartWithTheInitiatorAndSkipTheCurrentStep() {
        List<EarlierActor> all = SodGuardListener.earlierActors(Set.of(), "p1_joint", "p1.clerk", "CLERK-DDO",
                List.of(entry("initiate", "p1.clerk", "CLERK-DDO"), entry("sc_scrutiny", "p1.ao2", "AO-2"),
                        entry("p1_joint", "p1.se", "SE-CIRCLE"), entry(SANCTION, "p1.ce", "CE-ZONE")));
        assertEquals(List.of(new EarlierActor("initiator", "p1.clerk", "CLERK-DDO"),
                new EarlierActor("sc_scrutiny", "p1.ao2", "AO-2"), new EarlierActor(SANCTION, "p1.ce", "CE-ZONE")),
                all);
    }

    @Test
    void onePersonHoldingTwoJointSeatsKeepsOnlyTheFirstSeat() {
        Map<String, String> holder = Map.of("SE-CIRCLE", "p1.se", "EE-DIV1", "p1.se", "CE-ZONE", "p1.ce");
        java.util.function.Function<String, java.util.Optional<String>> of =
                s -> java.util.Optional.ofNullable(holder.get(s));
        assertEquals(List.of("SE-CIRCLE", "CE-ZONE"),
                SeatResolutionDelegate.firstSeatPerHolder(List.of("SE-CIRCLE", "EE-DIV1", "CE-ZONE"), of));
        assertEquals(List.of("EE-DIV1", "CE-ZONE"),
                SeatResolutionDelegate.firstSeatPerHolder(List.of("EE-DIV1", "SE-CIRCLE", "CE-ZONE"), of),
                "the first seat in the configured order stays");
        assertEquals(List.of("VACANT-1", "VACANT-2", "SE-CIRCLE"),
                SeatResolutionDelegate.firstSeatPerHolder(List.of("VACANT-1", "VACANT-2", "SE-CIRCLE"), of),
                "a seat without a holder today is not a duplicate of another");
    }

    @Test
    void aSecondCompletionBySamePersonInOneRoundIsRefusedAnotherPersonIsNot() {
        String round = "p1_joint#multiInstanceBody:R1";
        List<Map<String, Object>> log = List.of(entry("sc_scrutiny", "p1.ao2", "AO-2"),
                Map.of("step", "p1_joint", "person", "p1.se", "seat", "SE-CIRCLE", "round", round));
        assertTrue(SodGuardListener.actedInRound("p1.se", "p1_joint", round, log), "same person, same round");
        assertFalse(SodGuardListener.actedInRound("p1.ee2", "p1_joint", round, log), "another person");
        assertFalse(SodGuardListener.actedInRound("p1.se", "p1_joint", "p1_joint#multiInstanceBody:R2", log),
                "a later round after a return");
        assertFalse(SodGuardListener.actedInRound("p1.se", "p1_joint", null, log), "not a multi-instance task");
        assertFalse(SodGuardListener.actedInRound("p1.ao2", "p1_joint", round, log),
                "another step is the earlier-actor check's business, not the round's");
    }

    @Test
    void aPoolOfOnlyTheSanctionerLeavesNothingAndARepeatedSeatCountsOnce() {
        assertTrue(jointSeats(List.of("CE-ZONE"), Set.of(), p1Log("p1.ce", "CE-ZONE")).isEmpty(),
                "nothing left: the delegate refuses below min (fail closed)");
        assertEquals(List.of("SE-CIRCLE"), jointSeats(List.of("SE-CIRCLE", "SE-CIRCLE", "CE-ZONE"), Set.of(),
                p1Log("p1.ce", "CE-ZONE")), "one seat is one approval");
    }
}
