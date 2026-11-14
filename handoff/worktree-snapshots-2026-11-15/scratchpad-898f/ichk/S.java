import java.net.*; import java.nio.channels.*;
public class S { public static void main(String[] a) throws Exception {
  try (ServerSocket ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress()); Socket s = new Socket(InetAddress.getLoopbackAddress(), ss.getLocalPort())) { System.out.println("blocking tcp ok"); } catch (Exception e) { System.out.println("blocking FAIL " + e); }
  try (ServerSocketChannel ssc = ServerSocketChannel.open()) { ssc.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)); try (SocketChannel sc = SocketChannel.open(ssc.getLocalAddress())) { System.out.println("nio tcp ok"); } } catch (Exception e) { System.out.println("nio FAIL " + e); }
  try (ServerSocketChannel ssc = ServerSocketChannel.open()) { ssc.bind(new InetSocketAddress(0)); var addr = new InetSocketAddress(InetAddress.getLoopbackAddress(), ((InetSocketAddress)ssc.getLocalAddress()).getPort()); try (SocketChannel sc = SocketChannel.open(addr)) { System.out.println("nio wildcard ok"); } } catch (Exception e) { System.out.println("nio wildcard FAIL " + e); }
  try { Selector.open().close(); System.out.println("selector ok"); } catch (Exception e) { System.out.println("selector FAIL " + e); }
} }
