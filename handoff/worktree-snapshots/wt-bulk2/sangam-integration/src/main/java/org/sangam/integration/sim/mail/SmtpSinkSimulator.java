// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sim.mail;

import org.sangam.integration.sim.EffectsLedger;
import org.sangam.integration.sim.Fault;
import org.sangam.integration.sim.FaultInjector;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal in-process SMTP server (RFC 5321 subset) on the loopback interface and a free port, the mail sink of the
 * SIMULATOR connector profile (R60 gov.in e-mail adapter, air-gapped development).
 *
 * <p>Commands: {@code EHLO/HELO, MAIL FROM, RCPT TO, DATA} (dot-terminated, dot-unstuffing), {@code RSET, NOOP,
 * QUIT}; {@code VRFY} answers 252. There is no TLS and no AUTH: EHLO advertises only {@code 8BITMIME} and
 * {@code SIZE}; {@code STARTTLS} and {@code AUTH} answer 502. Each connection runs on its own virtual thread.
 *
 * <p>Received messages ({@link #messages()}) keep the envelope sender, the recipients, the raw DATA bytes and the
 * parsed {@code Message-ID} header. Ledger type {@code MAIL_SENT}, key = Message-ID (or {@code <no-message-id-N>}
 * when the message has none).
 *
 * <p>Faults (route name {@value #ROUTE}), taken when the DATA command arrives: DELAY waits; ERROR answers 451 to
 * DATA (nothing accepted, no effect); DROP_RESPONSE reads and stores the message (the effect happens) and then
 * closes the connection without the final 250 (the UNKNOWN_OUTCOME case, A-2); DUPLICATE stores the message twice.
 */
public final class SmtpSinkSimulator implements AutoCloseable {

    public static final String ROUTE = "smtp-data";
    public static final String EFFECT_MAIL_SENT = "MAIL_SENT";
    public static final String HOSTNAME = "sangam-smtp-sink";

    private static final int MAX_COMMAND_LINE = 4096;
    /** Identity marker returned by {@link #readData} for a message above the size limit. */
    private static final byte[] TOO_LARGE = new byte[0];

    /** One message accepted by the sink. */
    public record ReceivedMessage(long seq, String from, List<String> recipients, byte[] raw, String messageId,
                                  Instant receivedAt) {
        public ReceivedMessage {
            recipients = List.copyOf(recipients);
        }

        /** DATA content decoded as UTF-8 (8BITMIME). */
        public String data() {
            return new String(raw, StandardCharsets.UTF_8);
        }

        /** First header with that name (unfolded, trimmed), or null. */
        public String header(String name) {
            return SmtpSinkSimulator.header(raw, name);
        }
    }

    private final FaultInjector faults = new FaultInjector();
    private final EffectsLedger ledger = new EffectsLedger();
    private final List<ReceivedMessage> messages = new CopyOnWriteArrayList<>();
    private final List<Socket> open = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ServerSocket server;
    private volatile long maxMessageBytes = 10L * 1024 * 1024;
    private volatile int socketTimeoutMillis = 60_000;
    private volatile boolean started;
    private volatile boolean closed;

    public SmtpSinkSimulator() {
        try {
            server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Largest DATA size accepted (advertised as {@code SIZE}); default 10 MiB. */
    public SmtpSinkSimulator maxMessageBytes(long max) {
        this.maxMessageBytes = max;
        return this;
    }

    public SmtpSinkSimulator socketTimeout(int millis) {
        this.socketTimeoutMillis = millis;
        return this;
    }

    public synchronized SmtpSinkSimulator start() {
        if (!started) {
            started = true;
            executor.execute(this::acceptLoop);
        }
        return this;
    }

    public int port() {
        return server.getLocalPort();
    }

    public String host() {
        return server.getInetAddress().getHostAddress();
    }

    public List<ReceivedMessage> messages() {
        return List.copyOf(messages);
    }

    public EffectsLedger ledger() {
        return ledger;
    }

    public FaultInjector faults() {
        return faults;
    }

    public void clear() {
        messages.clear();
        ledger.clear();
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException e) {
            // closing anyway
        }
        for (Socket s : open) {
            closeQuietly(s);
        }
        executor.shutdownNow();
    }

    // ---- server --------------------------------------------------------------------------------

    private void acceptLoop() {
        while (!closed) {
            Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                continue;
            }
            open.add(s);
            try {
                executor.execute(() -> session(s));
            } catch (RuntimeException e) {
                closeQuietly(s);
                open.remove(s);
            }
        }
    }

    private void session(Socket socket) {
        try (socket) {
            socket.setSoTimeout(socketTimeoutMillis);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            reply(out, "220 " + HOSTNAME + " ESMTP Sangam simulator ready");
            boolean greeted = false;
            String from = null;
            List<String> rcpts = new ArrayList<>();
            while (true) {
                byte[] lineBytes = readLine(in, MAX_COMMAND_LINE);
                if (lineBytes == null) {
                    return;
                }
                String line = new String(lineBytes, StandardCharsets.UTF_8);
                String upper = line.toUpperCase(Locale.ROOT);
                String verb = upper.length() >= 4 ? upper.substring(0, 4) : upper;
                switch (verb) {
                    case "EHLO" -> {
                        greeted = true;
                        from = null;
                        rcpts.clear();
                        reply(out, "250-" + HOSTNAME + "\r\n250-8BITMIME\r\n250 SIZE " + maxMessageBytes);
                    }
                    case "HELO" -> {
                        greeted = true;
                        from = null;
                        rcpts.clear();
                        reply(out, "250 " + HOSTNAME);
                    }
                    case "MAIL" -> {
                        if (!greeted) {
                            reply(out, "503 5.5.1 send EHLO or HELO first");
                        } else if (from != null) {
                            reply(out, "503 5.5.1 sender already given");
                        } else if (!upper.startsWith("MAIL FROM:")) {
                            reply(out, "501 5.5.4 syntax: MAIL FROM:<address>");
                        } else {
                            String addr = path(line.substring("MAIL FROM:".length()));
                            if (addr == null) {
                                reply(out, "501 5.1.7 bad sender address syntax");
                            } else {
                                from = addr;
                                reply(out, "250 2.1.0 OK");
                            }
                        }
                    }
                    case "RCPT" -> {
                        if (from == null) {
                            reply(out, "503 5.5.1 need MAIL before RCPT");
                        } else if (!upper.startsWith("RCPT TO:")) {
                            reply(out, "501 5.5.4 syntax: RCPT TO:<address>");
                        } else {
                            String addr = path(line.substring("RCPT TO:".length()));
                            if (addr == null || addr.isEmpty()) {
                                reply(out, "501 5.1.3 bad recipient address syntax");
                            } else {
                                rcpts.add(addr);
                                reply(out, "250 2.1.5 OK");
                            }
                        }
                    }
                    case "DATA" -> {
                        if (from == null || rcpts.isEmpty()) {
                            reply(out, "503 5.5.1 need MAIL and RCPT before DATA");
                            continue;
                        }
                        FaultInjector.Active active = faults.next(ROUTE);
                        active.sleepDelay();
                        Fault error = active.error();
                        if (error != null) {
                            from = null;
                            rcpts.clear();
                            reply(out, "451 4.3.0 injected fault, message not accepted");
                            continue;
                        }
                        reply(out, "354 End data with <CR><LF>.<CR><LF>");
                        byte[] data = readData(in);
                        if (data == null) {
                            return;   // connection lost during DATA: nothing accepted
                        }
                        if (data == TOO_LARGE) {
                            from = null;
                            rcpts.clear();
                            reply(out, "552 5.3.4 message size exceeds fixed maximum message size");
                            continue;
                        }
                        String id = store(from, rcpts, data);
                        if (active.duplicate()) {
                            store(from, rcpts, data);
                        }
                        from = null;
                        rcpts.clear();
                        if (active.dropResponse()) {
                            return;   // message accepted, 250 never sent
                        }
                        reply(out, "250 2.0.0 OK queued as " + id);
                    }
                    case "RSET" -> {
                        from = null;
                        rcpts.clear();
                        reply(out, "250 2.0.0 OK");
                    }
                    case "NOOP" -> reply(out, "250 2.0.0 OK");
                    case "VRFY" -> reply(out, "252 2.5.2 cannot VRFY user");
                    case "QUIT" -> {
                        reply(out, "221 2.0.0 " + HOSTNAME + " closing connection");
                        return;
                    }
                    case "AUTH", "STAR" -> reply(out, "502 5.5.1 command not implemented");
                    default -> reply(out, "500 5.5.2 command not recognized");
                }
            }
        } catch (SocketException e) {
            // client went away or the sink is closing
        } catch (IOException e) {
            // read timeout or broken connection: the session ends
        } finally {
            open.remove(socket);
        }
    }

    private String store(String from, List<String> rcpts, byte[] data) {
        long n = seq.incrementAndGet();
        String messageId = header(data, "Message-ID");
        ReceivedMessage m = new ReceivedMessage(n, from, rcpts, data, messageId, Instant.now());
        messages.add(m);
        String key = messageId == null || messageId.isBlank() ? "<no-message-id-" + n + ">" : messageId;
        ledger.record(EFFECT_MAIL_SENT, key, Map.of("from", from, "recipients", String.join(",", rcpts),
                "size", String.valueOf(data.length)));
        return "SIM" + String.format("%08d", n);
    }

    /**
     * Reads the DATA section up to the line {@code "."}, undoing dot-stuffing. Lines are kept with CRLF ends. Returns
     * null if the connection ends first. Content beyond {@link #maxMessageBytes} is read and discarded, and the
     * {@link #TOO_LARGE} marker is returned so that the caller answers 552.
     */
    private byte[] readData(InputStream in) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        long limit = maxMessageBytes;
        boolean overflow = false;
        while (true) {
            byte[] line = readLine(in, Integer.MAX_VALUE);
            if (line == null) {
                return null;
            }
            if (line.length == 1 && line[0] == '.') {
                break;
            }
            int off = line.length > 0 && line[0] == '.' ? 1 : 0;
            if (!overflow) {
                if (data.size() + (line.length - off) + 2L > limit) {
                    overflow = true;
                } else {
                    data.write(line, off, line.length - off);
                    data.write('\r');
                    data.write('\n');
                }
            }
        }
        return overflow ? TOO_LARGE : data.toByteArray();
    }

    /** One line without its CRLF (a bare LF is accepted); null at end of stream. */
    private static byte[] readLine(InputStream in, int max) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int c;
        int count = 0;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                byte[] b = buf.toByteArray();
                int len = b.length;
                if (len > 0 && b[len - 1] == '\r') {
                    byte[] t = new byte[len - 1];
                    System.arraycopy(b, 0, t, 0, len - 1);
                    return t;
                }
                return b;
            }
            if (++count > max) {
                throw new IOException("line too long");
            }
            buf.write(c);
        }
        return null;
    }

    /** Address inside {@code <...>} of a MAIL/RCPT argument (parameters after it ignored); "" for {@code <>}. */
    private static String path(String arg) {
        String a = arg.trim();
        int lt = a.indexOf('<');
        int gt = a.indexOf('>', lt + 1);
        if (lt != 0 || gt < 0) {
            return null;
        }
        return a.substring(1, gt).trim();
    }

    private static void reply(OutputStream out, String text) throws IOException {
        out.write((text + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** First header {@code name} of an RFC 5322 message (header block up to the first empty line, unfolded). */
    static String header(byte[] raw, String name) {
        String text = new String(raw, StandardCharsets.UTF_8);
        String prefix = name.toLowerCase(Locale.ROOT) + ":";
        String current = null;
        for (String line : text.split("\r?\n", -1)) {
            if (line.isEmpty()) {
                break;
            }
            if ((line.startsWith(" ") || line.startsWith("\t")) && current != null) {
                current = current + " " + line.trim();
                continue;
            }
            if (current != null) {
                return current.trim();
            }
            if (line.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                current = line.substring(prefix.length());
            }
        }
        return current == null ? null : current.trim();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException e) {
            // ignore
        }
    }
}
