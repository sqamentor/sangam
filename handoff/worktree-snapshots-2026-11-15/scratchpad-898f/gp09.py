p = r"D:\GITHUB  Project\Custom WorkFlow\sangam\sangam-server\src\test\java\org\sangam\server\acceptance\gov\E16aPatternsIT.java"
s = open(p, encoding="utf-8").read()
a = s.index("    @Test\n    void gp09_largeMultiInstance")
b = s.index("    // =====================================================================================================  GP-10")
new = '''    /**
     * GP-09. Sangam replacement (amendment "GP-09 rule: one instance per item; no large multi-instance", D-23): the
     * spike measured n-squared completion of a 5,000-item parallel multi-instance body (1,732 of 5,000 in 180 s on
     * Operaton). Sangam's MultiInstanceSizeGuard refuses a multi-instance above the configured maximum with a clear
     * message; beneficiary lists use one instance per item (the GP-08 shape, covered there). This case checks the
     * guard at the spike's scale and the spike's own completion checks at the allowed maximum.
     */
    @Test
    void gp09_largeMultiInstance() {
        scenario("GP-09", "Large multi-instance (>= 5,000 items in one instance) -> Sangam GP-09 guard", r -> {
            int n = scaleN();
            int max = settings.getMultiInstanceMaxCardinality();
            r.feature("parallel multi-instance userTask, loopCardinality ${n}", "Sangam MultiInstanceSizeGuard (D-23)");
            String bpmn = defs("<process id=\\"gp09\\" isExecutable=\\"true\\"><startEvent id=\\"s\\"/>" + flow("f1", "s", "verify")
                    + "<userTask id=\\"verify\\" name=\\"verify\\"><potentialOwner><resourceAssignmentExpression><formalExpression>"
                    + "group(SEAT-VERIFIER)</formalExpression></resourceAssignmentExpression></potentialOwner>"
                    + "<multiInstanceLoopCharacteristics isSequential=\\"false\\"><loopCardinality xsi:type=\\"tFormalExpression\\">"
                    + "${n}</loopCardinality></multiInstanceLoopCharacteristics></userTask>"
                    + flow("f2", "verify", "summary") + userTask("summary", "SEAT-DDO") + flow("f3", "summary", "e")
                    + "<endEvent id=\\"e\\"/></process>");
            deploy("gp09", "gp09.bpmn", bpmn);
            r.detail("multiInstanceMaxCardinality", max);
            String guardError = null;
            try {
                start("gp09", "BENEFICIARY-LIST-1", "n", n);
            } catch (RuntimeException e) {
                guardError = GovARec.errorText(e);
            }
            r.detail("guardError", guardError);
            r.check(guardError != null && guardError.contains("GP-09"), "a " + n + "-item multi-instance is refused by the "
                    + "GP-09 guard with a clear message");
            r.check(k().runningInstances(a, "gp09") == 0, "the refused start left no running instance");
            // at the allowed maximum the spike's checks hold
            String pid = start("gp09", "BENEFICIARY-LIST-2", "n", max);
            long open = a.countTasks(new TaskQuery(null, null, List.of("SEAT-VERIFIER"), pid, null, 0, 0));
            r.check(open == max, max + " item tasks open (got " + open + ")");
            long t0 = System.nanoTime();
            for (String id : k().taskIds(a, pid, null, "verify", max + 10)) {
                a.completeTask(id, Map.of());
            }
            r.timing("complete_" + max + "_items_1_thread", ms(t0));
            r.check(tasks(pid, "summary").size() == 1, "summary task after the last item");
            a.completeTask(one(pid, "summary").id(), Map.of());
            r.check(ended(pid), "instance ended");
        });
    }

'''
open(p, "w", encoding="utf-8").write(s[:a] + new + s[b:])
print("ok")
