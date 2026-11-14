import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.sangam.engine.deploy.ExpressionPolicy;
import org.sangam.engine.deploy.ModelCodeAllowList;

public class Probe {
    public static void main(String[] a) throws Exception {
        Path root = Path.of(a[0]);
        List<Path> files;
        try (Stream<Path> s = Files.walk(root)) {
            files = s.filter(p -> {
                String r = root.relativize(p).toString().replace('\\', '/');
                if (r.contains("/target")) return false;
                String n = r.toLowerCase();
                boolean model = n.endsWith(".bpmn") || n.endsWith(".cmmn") || n.endsWith(".bpmn20.xml") || n.endsWith(".cmmn11.xml") || n.endsWith(".cmmn.xml");
                return (r.contains("/src/main/") && model) || (r.contains("ifms/e46/") && n.endsWith(".bpmn.model"));
            }).collect(Collectors.toList());
        }
        int bad = 0;
        for (Path p : files) {
            var f = ModelCodeAllowList.check(p.toString(), Files.readAllBytes(p), ModelCodeAllowList.SANGAM_CLASSES, ModelCodeAllowList.SANGAM_BEANS);
            if (!f.isEmpty()) { bad++; System.out.println(p + " " + f); }
        }
        System.out.println("models " + files.size() + " refused " + bad);
        for (String e : List.of("${execution.processEngine}", "${unapprovedBean.x}", "${unapprovedBean['x']}", "${x.getClass()}",
                "${(c ? execution : x).processEngineServices}", "${committeeSeats.split(',')[loopCounter]}", "${now()}",
                "${x.concat('')['concat']('y')}", "${execution.variables[k]}", "${a.}", "${moveSteps.prepare(execution)}",
                "${decisionResult.getSingleResult().get('route')}", "${members.split(',').length}")) {
            System.out.println(e + " -> " + ExpressionPolicy.check(e, ModelCodeAllowList.SANGAM_BEANS));
        }
    }
}
