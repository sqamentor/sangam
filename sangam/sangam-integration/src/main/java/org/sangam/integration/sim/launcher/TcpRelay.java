// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sim.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Byte relay from the container's SMTP port to the SMTP sink on the loopback interface (owner decision 184). Each
 * connection gets its own connection to the sink; when either side closes, both are closed, so a sink that drops the
 * connection after DATA (fault {@code DROP_RESPONSE}) is seen by the caller exactly as in process.
 */
final class TcpRelay implements AutoCloseable {

    private final ServerSocket server;
    private final InetSocketAddress target;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Socket> open = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    TcpRelay(InetSocketAddress listen, InetSocketAddress target) throws IOException {
        this.target = target;
        this.server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(listen);
    }

    TcpRelay start() {
        executor.execute(this::accept);
        return this;
    }

    int port() {
        return server.getLocalPort();
    }

    private void accept() {
        while (!closed) {
            Socket client;
            try {
                client = server.accept();
            } catch (IOException e) {
                return; // closed
            }
            executor.execute(() -> relay(client));
        }
    }

    private void relay(Socket client) {
        open.add(client);
        try (client; Socket upstream = new Socket()) {
            upstream.connect(target, 10_000);
            open.add(upstream);
            Runnable down = () -> pump(upstream, client);
            executor.execute(down);
            pump(client, upstream);
        } catch (IOException e) {
            // the caller sees the connection close
        } finally {
            open.remove(client);
        }
    }

    /** Copies {@code from} to {@code to} until {@code from} ends, then closes both. */
    private void pump(Socket from, Socket to) {
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException e) {
            // either side went away
        } finally {
            quietClose(from);
            quietClose(to);
            open.remove(from);
            open.remove(to);
        }
    }

    private static void quietClose(Socket s) {
        try {
            s.close();
        } catch (IOException e) {
            // closing anyway
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException e) {
            // closing anyway
        }
        open.forEach(TcpRelay::quietClose);
        executor.shutdownNow();
    }
}
