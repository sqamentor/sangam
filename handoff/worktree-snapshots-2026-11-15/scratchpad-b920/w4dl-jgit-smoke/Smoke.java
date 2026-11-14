import org.eclipse.jgit.api.Git;
import java.nio.file.*;
public class Smoke { public static void main(String[] a) throws Exception {
  Path d = Files.createTempDirectory("jgit-smoke");
  try (Git g = Git.init().setDirectory(d.toFile()).setInitialBranch("main").call()) {
    Files.writeString(d.resolve("a.txt"), "x");
    g.add().addFilepattern("a.txt").call();
    var c = g.commit().setMessage("m").setAuthor("t","t@example.invalid").setCommitter("t","t@example.invalid").setSign(false).call();
    System.out.println("commit " + c.getName() + " java " + Runtime.version());
  } } }
