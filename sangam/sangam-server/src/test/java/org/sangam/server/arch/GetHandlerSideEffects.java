// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.arch;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.asm.AnnotationVisitor;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;

/**
 * Round 9 CSRF triage: which side effects can a GET handler really cause?
 *
 * <p>{@link GetHandlersNeverWriteTest} uses CodeQL's dispatch model, in which a call of a functional interface
 * (for example {@code Supplier.get()}) reaches <b>every</b> lambda of that interface in the program, test code included.
 * That model cannot tell a real write from a lambda that no request can ever reach. This analysis keeps class
 * dispatch as CodeQL does (every override in a subtype) but resolves a lambda call only to lambdas the request can
 * actually hold:
 * <ul>
 *   <li>lambdas and method references created in a method this handler reaches (the work it passes down), and</li>
 *   <li>lambdas created while the application is wired (constructors, static initialisers, {@code @Bean} methods
 *       and {@code *Wiring}/{@code *Config*} classes: strategies stored in fields, such as clocks and resolvers).</li>
 * </ul>
 * Main code only (test classes are not on a request's path). Side effects looked for (owner rule 4): JDBC writes,
 * write transactions, Operaton commands that change engine state, outbound HTTP, file writes.
 */
final class GetHandlerSideEffects {

    static final String GET_MAPPING = "Lorg/springframework/web/bind/annotation/GetMapping;";
    static final String REQUEST_MAPPING = "Lorg/springframework/web/bind/annotation/RequestMapping;";
    static final String BEAN = "Lorg/springframework/context/annotation/Bean;";

    private static final Set<String> JDBC = Set.of("java/sql/Statement", "java/sql/PreparedStatement",
            "java/sql/CallableStatement");
    private static final Set<String> JDBC_WRITES = Set.of("executeUpdate", "executeLargeUpdate", "executeBatch",
            "executeLargeBatch");
    /** Operaton engine API calls that change engine state (name-based; reviewed per site). */
    private static final Pattern ENGINE_MUTATION = Pattern.compile(
            "(start|complete|claim|unclaim|delegate|resolve|setAssignee|setOwner|setPriority|setDueDate|setVariable.*"
                    + "|removeVariable.*|delete.*|save.*|submit.*|correlate.*|signal.*|messageEventReceived|deploy"
                    + "|activate.*|suspend.*|modify.*|execute|executeAsync|executeWithVariablesInReturn|evaluate"
                    + "|setRetries|setJobRetries.*|set.*|handleFailure|handleBpmnError|extendLock|unlock|lock|fetchAndLock"
                    + "|create(?!.*Query).*|newTask|newUser|newGroup|newTenant|add.*|update.*|close.*|terminate.*"
                    + "|reenable.*|disable.*|manualStart.*|cancel.*|restart.*|migrate.*|recalculate.*|cleanUp.*)");
    private static final Pattern ENGINE_READ = Pattern.compile(
            "(get.*|is.*|has.*|list.*|count|singleResult|create.*Query|createNative.*|.*Query|find.*|toString|equals|hashCode)");
    private static final Set<String> HTTP = Set.of("java/net/http/HttpClient", "java/net/HttpURLConnection",
            "java/net/URLConnection", "org/springframework/web/client/RestTemplate", "org/springframework/web/client/RestClient");
    private static final Pattern FILE_WRITE = Pattern.compile(
            "(write.*|delete.*|move|copy|createFile|createDirector.*|createTemp.*|newOutputStream|newBufferedWriter)");

    record M(String owner, String name, String desc) {
        @Override
        public String toString() {
            return owner.replace('/', '.') + "." + name;
        }
    }

    /** A call instruction in a method: the target as written. */
    record Call(String owner, String name, String desc) {
    }

    final Map<String, String> superOf = new HashMap<>();
    final Map<String, List<String>> interfacesOf = new HashMap<>();
    final Map<String, Set<String>> subtypes = new HashMap<>();
    final Set<String> configClasses = new HashSet<>();
    final Set<M> declared = new HashSet<>();
    final Map<M, Set<Call>> calls = new HashMap<>();
    /** Lambdas created in a method: key {@code iface.sam} -> impl. */
    final Map<M, Map<String, Set<M>>> creates = new HashMap<>();
    final Map<String, Set<M>> wiringLambdas = new HashMap<>();
    final Set<M> beanMethods = new HashSet<>();
    final Set<M> sources = new LinkedHashSet<>();
    private final Map<Call, Set<M>> classTargets = new HashMap<>();

    // ------------------------------------------------------------------ analysis

    /** Side effect kind of a call, or null. */
    static String sinkOf(Call c) {
        if (JDBC.contains(c.owner()) && JDBC_WRITES.contains(c.name())) {
            return "jdbc-write " + c.name();
        }
        if (c.owner().startsWith("org/operaton/bpm/engine/") && !c.owner().startsWith("org/operaton/bpm/engine/variable/")
                && !c.owner().contains("/query/") && !c.owner().endsWith("Query")
                && !ENGINE_READ.matcher(c.name()).matches() && ENGINE_MUTATION.matcher(c.name()).matches()) {
            return "engine " + c.owner().substring(c.owner().lastIndexOf('/') + 1) + "." + c.name();
        }
        if (HTTP.contains(c.owner()) && (c.name().startsWith("send") || c.name().equals("connect")
                || c.name().equals("getOutputStream") || c.name().startsWith("exchange") || c.name().startsWith("post")
                || c.name().startsWith("put") || c.name().startsWith("delete") || c.name().startsWith("patch"))) {
            return "http " + c.owner().substring(c.owner().lastIndexOf('/') + 1) + "." + c.name();
        }
        if ("java/nio/file/Files".equals(c.owner()) && FILE_WRITE.matcher(c.name()).matches()) {
            return "file " + c.name();
        }
        if ("org/sangam/core/layer/Transactions".equals(c.owner()) && "inTransaction".equals(c.name())
                || "org/sangam/core/api/EngineAdapter".equals(c.owner()) && "inEngineTransaction".equals(c.name())) {
            return "write-tx " + c.name();
        }
        return null;
    }

    /** One reached side effect: the site (caller -> sink) and a path from the handler. */
    record Hit(String sink, M site, List<M> path) {
    }

    /** Every side effect {@code source} can reach, one shortest path per (site, sink). */
    List<Hit> reach(M source) {
        Map<M, M> parent = new HashMap<>();
        Deque<M> work = new ArrayDeque<>();
        Map<String, Set<M>> available = new HashMap<>();
        wiringLambdas.forEach((k, v) -> available.computeIfAbsent(k, x -> new HashSet<>()).addAll(v));
        Map<String, Set<M>> waiting = new HashMap<>(); // lambda key -> callers waiting for lambdas of that key
        List<Hit> hits = new ArrayList<>();
        Set<String> seenSites = new HashSet<>();
        parent.put(source, source);
        work.add(source);
        if (!declared.contains(source)) {
            for (M t : targets(new Call(source.owner(), source.name(), source.desc()), Map.of(), null, null)) {
                parent.putIfAbsent(t, source);
                work.add(t);
            }
        }
        while (!work.isEmpty()) {
            M m = work.pop();
            for (Map.Entry<String, Set<M>> e : creates.getOrDefault(m, Map.of()).entrySet()) {
                Set<M> av = available.computeIfAbsent(e.getKey(), x -> new HashSet<>());
                for (M lam : e.getValue()) {
                    if (av.add(lam)) {
                        for (M waiter : waiting.getOrDefault(e.getKey(), Set.of())) {
                            if (!parent.containsKey(lam)) {
                                parent.put(lam, waiter);
                                work.add(lam);
                            }
                        }
                    }
                }
            }
            for (Call c : calls.getOrDefault(m, Set.of())) {
                String sink = sinkOf(c);
                if (sink != null) {
                    if (seenSites.add(sink + "@" + m)) {
                        hits.add(new Hit(sink, m, pathTo(parent, m)));
                    }
                    continue;
                }
                for (M t : targets(c, available, waiting, m)) {
                    if (!parent.containsKey(t)) {
                        parent.put(t, m);
                        work.add(t);
                    }
                }
            }
        }
        return hits;
    }

    private static List<M> pathTo(Map<M, M> parent, M m) {
        List<M> path = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            path.add(0, m);
            M p = parent.get(m);
            if (p == null || p.equals(m)) {
                break;
            }
            m = p;
        }
        return path;
    }

    private Set<M> targets(Call c, Map<String, Set<M>> available, Map<String, Set<M>> waiting, M caller) {
        Set<M> out = new HashSet<>(classTargets.computeIfAbsent(c, this::classDispatch));
        for (String sub : allSubtypes(c.owner())) {
            String key = sub + "." + c.name();
            out.addAll(available.getOrDefault(key, Set.of()));
            if (waiting != null) {
                waiting.computeIfAbsent(key, k -> new HashSet<>()).add(caller);
            }
        }
        return out;
    }

    private Set<M> classDispatch(Call c) {
        Set<M> out = new HashSet<>();
        for (String sup : allSupertypes(c.owner())) {
            M m = new M(sup, c.name(), c.desc());
            if (declared.contains(m)) {
                out.add(m);
            }
        }
        for (String sub : allSubtypes(c.owner())) {
            M m = new M(sub, c.name(), c.desc());
            if (declared.contains(m)) {
                out.add(m);
            }
        }
        return out;
    }

    private final Map<String, Set<String>> subCache = new HashMap<>();
    private final Map<String, Set<String>> supCache = new HashMap<>();

    Set<String> allSubtypes(String t) {
        return subCache.computeIfAbsent(t, x -> {
            Set<String> out = new HashSet<>();
            Deque<String> q = new ArrayDeque<>(List.of(x));
            while (!q.isEmpty()) {
                String c = q.pop();
                if (out.add(c)) {
                    q.addAll(subtypes.getOrDefault(c, Set.of()));
                }
            }
            return out;
        });
    }

    Set<String> allSupertypes(String t) {
        return supCache.computeIfAbsent(t, x -> {
            Set<String> out = new HashSet<>();
            Deque<String> q = new ArrayDeque<>(List.of(x));
            while (!q.isEmpty()) {
                String c = q.pop();
                if (c != null && out.add(c)) {
                    if (superOf.containsKey(c)) {
                        q.add(superOf.get(c));
                    }
                    q.addAll(interfacesOf.getOrDefault(c, List.of()));
                }
            }
            return out;
        });
    }

    /** handler -> hits, for every GET handler. */
    Map<M, List<Hit>> analyse() {
        Map<M, List<Hit>> out = new LinkedHashMap<>();
        for (M s : sources) {
            out.put(s, reach(s));
        }
        return out;
    }

    /** sink site -> handlers, as text lines for review. */
    static List<String> bySite(Map<M, List<Hit>> result) {
        Map<String, Set<String>> handlers = new TreeMap<>();
        Map<String, List<M>> example = new HashMap<>();
        result.forEach((h, hits) -> hits.forEach(hit -> {
            String key = hit.sink() + " @ " + hit.site();
            handlers.computeIfAbsent(key, k -> new TreeSet<>()).add(h.toString());
            example.putIfAbsent(key, hit.path());
        }));
        List<String> lines = new ArrayList<>();
        handlers.forEach((k, hs) -> {
            lines.add(k + "  [" + hs.size() + " GET handlers]");
            lines.add("    path: " + String.join(" -> ", example.get(k).stream().map(M::toString).toList()));
            hs.forEach(h -> lines.add("    " + h));
        });
        return lines;
    }

    // ------------------------------------------------------------------ bytecode (main classes only)

    static GetHandlerSideEffects load() {
        GetHandlerSideEffects g = new GetHandlerSideEffects();
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            Path p = Path.of(entry);
            try {
                if (Files.isDirectory(p) && !p.toString().replace('\\', '/').endsWith("/test-classes")) {
                    Path root = p.resolve("org/sangam");
                    if (Files.isDirectory(root)) {
                        try (Stream<Path> s = Files.walk(root)) {
                            for (Path f : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".class"))::iterator) {
                                try (InputStream in = Files.newInputStream(f)) {
                                    g.read(in);
                                }
                            }
                        }
                    }
                } else if (entry.endsWith(".jar") && p.getFileName().toString().startsWith("sangam-")
                        && !p.getFileName().toString().contains("-tests")) {
                    try (JarFile jar = new JarFile(p.toFile())) {
                        for (JarEntry je : (Iterable<JarEntry>) jar.stream()::iterator) {
                            if (je.getName().startsWith("org/sangam/") && je.getName().endsWith(".class")) {
                                try (InputStream in = jar.getInputStream(je)) {
                                    g.read(in);
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        // lambdas created while wiring the application are strategies a request can hold
        g.creates.forEach((m, byKey) -> {
            boolean wiring = m.name().equals("<init>") || m.name().equals("<clinit>") || g.beanMethods.contains(m)
                    || g.configClasses.contains(m.owner());
            if (wiring) {
                byKey.forEach((k, v) -> g.wiringLambdas.computeIfAbsent(k, x -> new HashSet<>()).addAll(v));
            }
        });
        return g;
    }

    private void read(InputStream in) throws IOException {
        new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
            String owner;

            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                              String[] interfaces) {
                owner = name;
                String simple = name.substring(name.lastIndexOf('/') + 1).split("\\$")[0];
                if (simple.endsWith("Wiring") || simple.contains("Config") || simple.endsWith("Configuration")) {
                    configClasses.add(name);
                }
                if (superName != null) {
                    superOf.put(name, superName);
                    subtypes.computeIfAbsent(superName, k -> new HashSet<>()).add(name);
                }
                List<String> ifs = interfaces == null ? List.of() : List.of(interfaces);
                interfacesOf.put(name, ifs);
                ifs.forEach(i -> subtypes.computeIfAbsent(i, k -> new HashSet<>()).add(name));
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if ("Lorg/springframework/context/annotation/Configuration;".equals(descriptor)) {
                    configClasses.add(owner);
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] ex) {
                M self = new M(owner, name, desc);
                declared.add(self);
                Set<Call> out = calls.computeIfAbsent(self, k -> new LinkedHashSet<>());
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        if (BEAN.equals(descriptor)) {
                            beanMethods.add(self);
                        }
                        if (GET_MAPPING.equals(descriptor)) {
                            sources.add(self);
                            return null;
                        }
                        if (!REQUEST_MAPPING.equals(descriptor)) {
                            return null;
                        }
                        Set<String> methods = new HashSet<>();
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitArray(String n) {
                                return "method".equals(n) ? new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitEnum(String n2, String d, String value) {
                                        methods.add(value);
                                    }
                                } : null;
                            }

                            @Override
                            public void visitEnd() {
                                if (methods.isEmpty() || methods.contains("GET") || methods.contains("HEAD")) {
                                    sources.add(self);
                                }
                            }
                        };
                    }

                    @Override
                    public void visitMethodInsn(int op, String o, String n, String d, boolean itf) {
                        if (!o.startsWith("[")) {
                            out.add(new Call(o, n, d));
                        }
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                        if (!"java/lang/invoke/LambdaMetafactory".equals(bsm.getOwner()) || args.length < 2
                                || !(args[1] instanceof Handle impl)) {
                            return;
                        }
                        String key = Type.getReturnType(d).getInternalName() + "." + n;
                        creates.computeIfAbsent(self, k -> new HashMap<>()).computeIfAbsent(key, k -> new HashSet<>())
                                .add(new M(impl.getOwner(), impl.getName(), impl.getDesc()));
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
    }

    static void writeReport(Path file, List<String> lines) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
