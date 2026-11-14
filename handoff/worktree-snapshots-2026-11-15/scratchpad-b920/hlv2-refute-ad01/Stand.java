import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Stand-in for the Maven JVM: prints to stdout (the runner's pipe, like Maven's console log) and appends a
 *  progress line through its OWN file handle every 500 ms, so it is visible whether it keeps working. */
public class Stand {
    public static void main(String[] a) throws Exception {
        Path progress = Path.of(a[0]);
        int seconds = Integer.parseInt(a[1]);
        long end = System.currentTimeMillis() + seconds * 1000L;
        long pid = ProcessHandle.current().pid();
        int i = 0;
        while (System.currentTimeMillis() < end) {
            i++;
            System.out.println("[INFO] stand-in maven tick " + i);
            Files.writeString(progress, Instant.now() + " tick " + i + " pid " + pid + " stdoutError=" + System.out.checkError()
                    + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Thread.sleep(500);
        }
        System.out.println("[INFO] BUILD SUCCESS (stand-in)");
        Files.writeString(progress, Instant.now() + " END pid " + pid + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
