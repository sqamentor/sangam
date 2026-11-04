package org.sangam.spike.load;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JSON result files (merged run by run), latency percentiles, CPU sampling and environment facts. */
public final class Results {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private Results() {
    }

    public static Path dir() {
        return Path.of(System.getProperty("results.dir", "../results"));
    }

    /** Sets {@code runs.<k1>.<k2>...} in {@code file} to {@code value}, keeping everything else, and sets the header. */
    public static synchronized void merge(String file, Map<String, Object> header, Object value, String... keys) {
        try {
            Path p = dir().resolve(file);
            Files.createDirectories(p.getParent());
            ObjectNode root = Files.exists(p) ? (ObjectNode) JSON.readTree(p.toFile()) : JSON.createObjectNode();
            header.forEach((k, v) -> root.set(k, JSON.valueToTree(v)));
            ObjectNode node = root.has("runs") ? (ObjectNode) root.get("runs") : root.putObject("runs");
            for (int i = 0; i < keys.length - 1; i++) {
                node = node.has(keys[i]) ? (ObjectNode) node.get(keys[i]) : node.putObject(keys[i]);
            }
            node.set(keys[keys.length - 1], JSON.valueToTree(value));
            root.put("updatedAt", Instant.now().toString());
            JSON.writeValue(p.toFile(), root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ latency

    /** Command latencies by command name, in microseconds. */
    public static final class Latencies {
        private final Map<String, ConcurrentLinkedQueue<Long>> byCommand = new ConcurrentHashMap<>();
        private volatile boolean recording;

        public void recording(boolean on) {
            recording = on;
        }

        public void record(String command, long nanos) {
            if (recording) {
                byCommand.computeIfAbsent(command, k -> new ConcurrentLinkedQueue<>()).add(nanos / 1000);
            }
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            List<Long> all = new ArrayList<>();
            new java.util.TreeMap<>(byCommand).forEach((k, q) -> {
                List<Long> l = new ArrayList<>(q);
                all.addAll(l);
                m.put(k, percentiles(l));
            });
            m.put("ALL_COMMANDS", percentiles(all));
            return m;
        }
    }

    public static Map<String, Object> percentiles(List<Long> micros) {
        long[] a = micros.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(a);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", a.length);
        if (a.length == 0) {
            return m;
        }
        m.put("p50_ms", ms(at(a, 0.50)));
        m.put("p95_ms", ms(at(a, 0.95)));
        m.put("p99_ms", ms(at(a, 0.99)));
        m.put("max_ms", ms(a[a.length - 1]));
        m.put("mean_ms", ms((long) Arrays.stream(a).average().orElse(0)));
        return m;
    }

    private static long at(long[] sorted, double q) {
        int i = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    private static double ms(long micros) {
        return Math.round(micros / 10.0) / 100.0;
    }

    // ------------------------------------------------------------------ CPU

    /** Samples JVM process CPU, machine CPU (JVM view) and the DB container CPU (docker stats) during a window. */
    public static final class CpuSampler implements AutoCloseable {
        private final com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        private final String containerId;
        private final List<Double> system = new ArrayList<>();
        private final List<Double> container = new ArrayList<>();
        private final List<String> containerMem = new ArrayList<>();
        private final Thread sysThread;
        private final Thread dockerThread;
        private volatile boolean running = true;
        private final long cpu0 = os.getProcessCpuTime();
        private final long wall0 = System.nanoTime();

        public CpuSampler(String containerId) {
            this.containerId = containerId;
            sysThread = Thread.ofPlatform().daemon().start(() -> {
                while (running) {
                    double v = os.getCpuLoad();
                    if (v >= 0) {
                        synchronized (system) {
                            system.add(v * 100);
                        }
                    }
                    sleep(1000);
                }
            });
            dockerThread = Thread.ofPlatform().daemon().start(() -> {
                while (running && containerId != null) {
                    String[] s = dockerStats(containerId);
                    if (s != null) {
                        try {
                            synchronized (container) {
                                container.add(Double.parseDouble(s[0].replace("%", "").trim()));
                                containerMem.add(s[1]);
                            }
                        } catch (NumberFormatException ignored) {
                            // skip sample
                        }
                    }
                    sleep(2000);
                }
            });
        }

        public Map<String, Object> stop() {
            running = false;
            long cpu = os.getProcessCpuTime() - cpu0;
            long wall = System.nanoTime() - wall0;
            int cpus = Runtime.getRuntime().availableProcessors();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("logicalCpus", cpus);
            m.put("jvmProcessCpuPercentOfMachine", round1(100.0 * cpu / wall / cpus));
            synchronized (system) {
                m.put("machineCpuPercentAvg", round1(system.stream().mapToDouble(d -> d).average().orElse(-1)));
            }
            synchronized (container) {
                m.put("dbContainerCpuPercentAvg_dockerStats", round1(container.stream().mapToDouble(d -> d).average().orElse(-1)));
                m.put("dbContainerCpuSamples", container.size());
                m.put("dbContainerCpuNote", "docker stats CPU% is relative to ONE core (100% = 1 core) of the Docker VM");
                if (!containerMem.isEmpty()) {
                    m.put("dbContainerMemLastSample", containerMem.get(containerMem.size() - 1));
                }
            }
            return m;
        }

        @Override
        public void close() {
            running = false;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** {@code [cpuPerc, memUsage]} from {@code docker stats --no-stream}, or null. */
    public static String[] dockerStats(String containerId) {
        String out = docker("stats", "--no-stream", "--format", "{{.CPUPerc}}|{{.MemUsage}}", containerId);
        if (out == null || !out.contains("|")) {
            return null;
        }
        return out.trim().split("\\|", 2);
    }

    public static String docker(String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("docker");
            cmd.addAll(List.of(args));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            p.waitFor();
            return sb.toString();
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Facts about the host and the other containers running at the same time (other agents share the machine). */
    public static Map<String, Object> environment() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", "FUNCTIONAL SCALE on an 8-logical-CPU / 32 GB Windows 11 dev machine, database co-located in "
                + "Docker on the same host. NOT the reference scale of R22.");
        m.put("javaVersion", System.getProperty("java.version"));
        m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        m.put("jvmAvailableProcessors", Runtime.getRuntime().availableProcessors());
        m.put("jvmMaxHeapMb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        String info = docker("info", "--format", "{{.ServerVersion}} cpus={{.NCPU}} mem={{.MemTotal}}");
        m.put("dockerInfo", info == null ? null : info.trim());
        String ps = docker("ps", "--format", "{{.Image}} ({{.Status}})");
        m.put("otherContainersAtStart", ps == null ? null : List.of(ps.trim().split("\n")));
        return m;
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static File file(String name) {
        return dir().resolve(name).toFile();
    }
}
