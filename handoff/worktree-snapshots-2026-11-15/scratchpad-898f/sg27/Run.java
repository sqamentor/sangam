import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
public class Run { public static void main(String[] a) throws Exception {
 var b = LauncherDiscoveryRequestBuilder.request(); for (String c : a) b.selectors(selectClass(Class.forName(c)));
 var l = new SummaryGeneratingListener(); LauncherFactory.create().execute(b.build(), l);
 l.getSummary().printTo(new java.io.PrintWriter(System.out)); l.getSummary().printFailuresTo(new java.io.PrintWriter(System.out), 15);
 System.exit(l.getSummary().getTotalFailureCount()==0?0:1);}}
