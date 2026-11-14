public class P { public static void main(String[] a) throws Exception {
  System.out.println("tmp=" + System.getProperty("java.io.tmpdir"));
  try { java.nio.channels.Pipe.open(); System.out.println("pipe ok"); } catch (Exception e) { System.out.println("pipe FAIL " + e); }
  try { java.net.http.HttpClient.newHttpClient(); System.out.println("httpclient ok"); } catch (Exception e) { System.out.println("http FAIL " + e); }
} }
