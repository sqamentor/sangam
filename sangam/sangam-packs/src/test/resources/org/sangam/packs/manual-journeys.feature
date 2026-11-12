# SPDX-License-Identifier: Apache-2.0
# M-116 / M-095 (Cucumber): the SL-32 journeys that were manual until the scenario clock and steering driver existed.
# Each one must stay automatic (no run.manual reason, a checkable outcome) and pass on the in-memory engine with the
# simulator, through the same runner as PackAcceptanceTest. The run data is generator data (packgen/acceptance_runs.py).
Feature: The SL-32 journeys that needed a clock or a steering driver run automatically

  Scenario Outline: <journey> of <pack> runs on the scenario clock and passes
    Given the acceptance scenario "<journey>" of the pack "<pack>"
    Then it has a checkable outcome and no manual reason
    When it is driven with its run steps on the scenario clock
    Then it reached the outcome its pack states
    And the scenario clock and the business clock are back where they were

    Examples:
      | pack                     | journey       |
      | sangam.core              | CORE-1        |
      | sangam.core              | CORE-2        |
      | sangam.core              | CORE-3        |
      | gov.p1.bill-payment      | AC-02         |
      | gov.p4.licence           | AC-06         |
      | gov.dbt.batch            | GP-49         |
      | gov.recruitment          | GP-47         |
      | gov.recruitment          | GP-28         |
      | gov.rti                  | GP-44         |
      | sec.bank.account-opening | SEC-BANK-AO-1 |
      | gov.p1.bill-payment      | J-P1-E11      |
      | gov.p1.bill-payment      | J-P1-E09      |
      | gov.p2.file-movement     | J-P2-E04      |
