p = r"D:\GITHUB  Project\Custom WorkFlow\sangam\sangam-server\src\test\java\org\sangam\server\acceptance\gov\E16aPatternsIT.java"
s = open(p, encoding="utf-8").read()
a = s.index('            String[] v1 = build.apply(1000, 5000);')
b = s.index('    // =====================================================================================================  GP-20')
new = '''            // Sangam replacement (D-12): the spike's third model (no binding = engine default) only documented which
            // rule an old instance silently picks. Sangam rejects a business rule task without an explicit
            // decisionRefBinding at deploy, so that case is now a refused deployment.
            String[] v1 = build.apply(1000, 5000);
            String noBindingError = null;
            try {
                deploy("gp19-nobinding", "gp19-fee.dmn", v1[0], "gp19L.bpmn", v1[2]);
            } catch (RuntimeException e) {
                noBindingError = GovARec.errorText(e);
            }
            r.detail("noBindingDeployError", noBindingError);
            r.check(noBindingError != null && noBindingError.contains("D-12"),
                    "a decision call without an explicit binding is refused at deploy (D-12)");
            deploy("gp19-v1", "gp19-fee.dmn", v1[0], "gp19.bpmn", v1[1]);
            String pA = start("gp19", "OLD-BOUND", "area", 50L);
            String[] v2 = build.apply(1500, 7500); // new fee notification
            deploy("gp19-v2", "gp19-fee.dmn", v2[0], "gp19.bpmn", v2[1]);
            String pB = start("gp19", "NEW-BOUND", "area", 50L);
            for (String p : List.of(pA, pB)) {
                a.completeTask(one(p, "apply").id(), Map.of());
            }
            double feeA = ((Number) var(pA, "fee")).doubleValue();
            double feeB = ((Number) var(pB, "fee")).doubleValue();
            r.detail("feeOldInstanceBoundToDeployment", feeA);
            r.detail("feeNewInstance", feeB);
            r.check(feeA == 1000, "old instance keeps the old rule (fee 1000), got " + feeA);
            r.check(feeB == 1500, "new instance uses the new rule (fee 1500), got " + feeB);
        });
    }

'''
open(p, "w", encoding="utf-8").write(s[:a] + new + s[b:])
print("ok")
