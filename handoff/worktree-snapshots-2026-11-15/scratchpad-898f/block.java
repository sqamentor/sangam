    @Test
    void nonWholeFeelNumbersLeaveTheEngineAsExactBigDecimal() {
        // SG-33 (was: Double). SangamValueMapper: non-whole -> BigDecimal, whole -> Long, never Double
        Object sum = lit("sum01_02");
        assertInstanceOf(BigDecimal.class, sum);
        assertEquals(0, new BigDecimal("0.3").compareTo((BigDecimal) sum));
        Object big = lit("bigPlus");
        assertInstanceOf(BigDecimal.class, big);
        assertEquals(0, new BigDecimal("123456789012345678.92").compareTo((BigDecimal) big),
                "18-digit amount + 0.01 leaves the engine exactly");
        assertInstanceOf(BigDecimal.class, lit("third"));
        assertEquals(9999999L, lit("rupeesToPaise"), "whole results come out as Long (exact)");
        assertEquals(Boolean.TRUE, lit("doubleIn"), "a Java double input 0.1 is read as decimal 0.1");
        Object sumOut = lit("sumOutputs");
        assertEquals(0, new BigDecimal("100000.3000000000000001").compareTo((BigDecimal) sumOut));
    }

    @Test
    void decisionTableOutputsAreExactAndSumExactly() {
        List<Map<String, Object>> rows = table("fees", Map.of("amount", new BigDecimal("1"))).getResultList();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            Object fee = row.get("fee");
            assertFalse(fee instanceof Double, "no Double output: " + fee);
            total = total.add(fee instanceof BigDecimal bd ? bd : BigDecimal.valueOf(((Number) fee).longValue()));
        }
        assertInstanceOf(BigDecimal.class, rows.get(0).get("fee"));
        assertEquals(0, new BigDecimal("0.3000000000000001").compareTo(total), "0.1 + 0.2 + 1E-16 exactly");
    }

    @Test
    void bandBoundariesInMinorUnitsAreExact() {
        assertEquals("A", band("bandPaise", "amountPaise", 9999899L));
        assertEquals("B", band("bandPaise", "amountPaise", 9999900L));   // 99,999.00
        assertEquals("B", band("bandPaise", "amountPaise", 9999999L));   // 99,999.99
        assertEquals("C", band("bandPaise", "amountPaise", 10000000L));  // 1,00,000.00
        assertEquals("D", band("bandPaise", "amountPaise", 10000001L));  // 1,00,000.01
        assertEquals("D", band("bandPaise", "amountPaise", 10000100L));  // 1,00,001.00
    }

    @Test
    void bandBoundariesInRupeesAreExact() {
        // SG-33 (was: the input passed through double, so 100000.0000000000000001 landed in MID). No typeRef and
        // typeRef="number" (the migration target of the 176 pack files that used "double") are both exact.
        for (String id : new String[] {"bandExact", "bandNumber"}) {
            assertEquals("LOW", band(id, "amount", new BigDecimal("99999")), id);
            assertEquals("MID", band(id, "amount", new BigDecimal("99999.01")), id);
            assertEquals("MID", band(id, "amount", new BigDecimal("99999.99")), id);
            assertEquals("MID", band(id, "amount", new BigDecimal("100000")), id);
            assertEquals("HIGH", band(id, "amount", new BigDecimal("100000.01")), id);
            assertEquals("HIGH", band(id, "amount", new BigDecimal("100001")), id);
            assertEquals("HIGH", band(id, "amount", new BigDecimal("100000.0000000000000001")), id);
            assertEquals("MID", band(id, "amount", 100000L), id);
            assertEquals("HIGH", band(id, "amount", "100000.0000000000000001".equals("") ? 0 : new BigDecimal("100000.0000000000000001")), id);
        }
        assertEquals("HIGH", band("bandNumber", "amount", "100000.0000000000000001"), "number accepts a decimal string exactly");
        assertEquals("MID", band("bandNumber", "amount", 100000.0d), "a double input is read by its decimal form");
    }

    @Test
    void moneyDoubleTypeRefIsRefusedAtDeployTime() {
        String dmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="bad" name="bad" namespace="t">
                """ + bandRupees("bandDouble", "double") + "</definitions>";
        RuntimeException e = assertThrows(RuntimeException.class, () -> engine.deploy("t1", "sg33-bad",
                Map.of("bad.dmn", dmn.getBytes(StandardCharsets.UTF_8))));
        assertTrue(chain(e).contains("SANGAM-DMN-MONEY-DOUBLE"), chain(e));
        assertEquals(0, engine.processEngine().getRepositoryService().createDecisionDefinitionQuery()
                .decisionDefinitionKey("bandDouble").count(), "nothing deployed");
    }

    // ------------------------------------------------------------------ null and error behaviour

    @Test
    void evaluationFailuresFailTheDecisionInsteadOfNull() {
        // SG-34 (was: each of these evaluated to null; ifNull silently took the else branch)
        for (String id : new String[] {"missingPlus", "missingIsNull", "nulPlus", "mismatch", "strPlusNum", "divZero",
                "unknownFn", "badDate", "ifNull", "ifNulCond", "customNull"}) {
            RuntimeException e = assertThrows(RuntimeException.class, () -> lit(id), id);
            assertTrue(chain(e).contains("SANGAM-FEEL-STRICT"), id + ": " + chain(e));
        }
    }

    @Test
    void genuineNullValuesStillWork() {
        assertNull(lit("nullVar"), "a present variable holding null is a value, not a failure");
        assertEquals(Boolean.TRUE, lit("nulIsNull"));
        assertNull(lit("customNullArg"), "a plugin function may return null for a null argument");
    }

    @Test
    void nullInputIsAnErrorUnlessARuleTestsNull() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", null);
        // SG-34 (was: empty result)
        RuntimeException strict = assertThrows(RuntimeException.class, () -> table("nullStrict", vars));
        assertTrue(chain(strict).contains("SANGAM-DMN-NULL-INPUT"), chain(strict));
        Map<String, Object> delay = new HashMap<>();
        delay.put("delayDays", null);
        // SG-34 (was: "penalty" from the catch-all "-" rule)
        RuntimeException catchAll = assertThrows(RuntimeException.class, () -> table("catchAll", delay));
        assertTrue(chain(catchAll).contains("SANGAM-DMN-NULL-INPUT"), chain(catchAll));
        assertEquals("none", table("catchAll", Map.of("delayDays", 0)).getSingleEntry());
        assertEquals("penalty", table("catchAll", Map.of("delayDays", 3)).getSingleEntry());
        // an explicit null rule still works, and a null input is not compared by the other rules
        assertEquals("unknown", table("nullRule", delay).getSingleEntry());
        assertEquals("none", table("nullRule", Map.of("delayDays", 0)).getSingleEntry());
        assertEquals("penalty", table("nullRule", Map.of("delayDays", 3)).getSingleEntry());
    }

    @Test
    void declaredTypeRefRejectsMistypedInput() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> table("nullStrict", Map.of("amount", "abc")));
        assertTrue(chain(e).contains("abc"), chain(e));
    }

    @Test
    void businessRuleTaskNullInputFailsTheCommandWithNoDownstreamEffect() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", null);
        // SG-34 (was: the task completed with ok = null)
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> engine.startProcess("t1", "feelRule", "SG27-NULL", vars));
        assertTrue(chain(e).contains("SANGAM-DMN-NULL-INPUT"), chain(e));
        assertEquals(0, engine.processEngine().getRuntimeService().createProcessInstanceQuery()
                .processInstanceBusinessKey("SG27-NULL").count(), "rolled back: no instance, no task");
        assertThrows(RuntimeException.class,
                () -> engine.startProcess("t1", "feelRule", "SG27-BAD", Map.of("amount", "abc")),
                "a mistyped input fails the transaction (incident if async), never a silent rule");
    }

    @Test
    void asyncBusinessRuleTaskFailureIsAnIncidentAndCanBeRetriedAfterCorrection() {
        var rt = engine.processEngine().getRuntimeService();
        var mgmt = engine.processEngine().getManagementService();
        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", null);
        String pi = engine.startProcess("t1", "feelRuleAsync", "SG34-INC", vars);
        var job = mgmt.createJobQuery().processInstanceId(pi).singleResult();
        for (int i = 0; i < 10 && mgmt.createJobQuery().jobId(job.getId()).singleResult().getRetries() > 0; i++) {
            assertThrows(RuntimeException.class, () -> mgmt.executeJob(job.getId()));
        }
        var incidents = rt.createIncidentQuery().processInstanceId(pi).list();
        assertEquals(1, incidents.size(), "incident on the business rule task");
        assertEquals("brt", incidents.get(0).getActivityId());
        assertTrue(incidents.get(0).getIncidentMessage().contains("SANGAM-DMN-NULL-INPUT"),
                incidents.get(0).getIncidentMessage());
        assertFalse(rt.getVariables(pi).containsKey("ok"), "no rule fired: no result variable");
        assertEquals(0, engine.processEngine().getTaskService().createTaskQuery().processInstanceId(pi).count(),
                "no downstream effect");
        // correction, then retry
        rt.setVariable(pi, "amount", 50);
        mgmt.setJobRetries(job.getId(), 1);
        mgmt.executeJob(job.getId());
        assertEquals(Boolean.TRUE, rt.getVariable(pi, "ok"));
        assertEquals(1, engine.processEngine().getTaskService().createTaskQuery().processInstanceId(pi).count());
        assertEquals(0, rt.createIncidentQuery().processInstanceId(pi).count(), "incident resolved");
    }

