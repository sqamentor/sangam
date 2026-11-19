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
    /** Wiring lambdas no field binding was found for (beans that are lambdas): available to every request. */
    final Map<String, Set<M>> wiringLambdas = new HashMap<>();
    /** Field ({@code owner.name}) -> lambdas stored in it while wiring, by lambda key. */
    final Map<String, Map<String, Set<M>>> fieldLambdas = new HashMap<>();
    /** Field declarations: class -> name -> descriptor. */
    final Map<String, Map<String, String>> fieldsOf = new HashMap<>();
    /** Fields a method reads, as written ({@code owner.name}). */
    final Map<M, Set<String>> fieldReads = new HashMap<>();
    /** Raw wiring bindings before resolution: {key, lambda, target "F:owner.name" | "C:class" | "U"}. */
    final List<Object[]> rawBindings = new ArrayList<>();
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
        return new Run(source).run();
    }

    /**
     * Context-sensitive walk. A lambda created on the path is callable only below the method that created it (the work
     * it hands down). Lambdas stored in fields while wiring become callable once a method on the path reads that field;
     * lambdas returned from a method (factories) and wiring lambdas without a field binding are callable anywhere in
     * this handler's walk.
     */
    private final class Run {
        record State(M m, int ctx) {
        }

        final M source;
        final Map<Set<M>, Integer> ctxIds = new HashMap<>();
        final List<Set<M>> ctxs = new ArrayList<>();
        final Map<String, Set<M>> global = new HashMap<>();
        final Map<String, Set<State>> waiting = new HashMap<>();
        final Map<State, State> parent = new HashMap<>();
        final Deque<State> work = new ArrayDeque<>();
        final List<Hit> hits = new ArrayList<>();
        final Set<String> seenSites = new HashSet<>();
        final Set<String> seenFields = new HashSet<>();

        Run(M source) {
            this.source = source;
            wiringLambdas.forEach((k, v) -> global.computeIfAbsent(k, x -> new HashSet<>()).addAll(v));
        }

        int ctxOf(Set<M> s) {
            return ctxIds.computeIfAbsent(s, x -> {
                ctxs.add(x);
                return ctxs.size() - 1;
            });
        }

        void push(State s, State from) {
            if (!parent.containsKey(s) && parent.size() < 3_000_000) {
                parent.put(s, from);
                work.add(s);
            }
        }

        void addGlobal(String key, M lam) {
            if (global.computeIfAbsent(key, x -> new HashSet<>()).add(lam)) {
                for (State w : waiting.getOrDefault(key, Set.of())) {
                    push(new State(lam, w.ctx()), w);
                }
            }
        }

        List<Hit> run() {
            State s0 = new State(source, ctxOf(Set.of()));
            parent.put(s0, s0);
            work.add(s0);
            if (!declared.contains(source)) {
                for (M t : classTargets.computeIfAbsent(new Call(source.owner(), source.name(), source.desc()),
                        GetHandlerSideEffects.this::classDispatch)) {
                    push(new State(t, s0.ctx()), s0);
                }
            }
            while (!work.isEmpty()) {
                State st = work.pop();
                M m = st.m();
                for (String f : fieldReads.getOrDefault(m, Set.of())) {
                    if (seenFields.add(f)) {
                        fieldLambdas.getOrDefault(resolveField(f), Map.of()).forEach((k, v) -> v.forEach(l -> addGlobal(k, l)));
                    }
                }
                Set<M> here = new HashSet<>(ctxs.get(st.ctx()));
                Map<String, Set<M>> made = creates.getOrDefault(m, Map.of());
                String ret = Type.getReturnType(m.desc()).getSort() == Type.OBJECT
                        ? Type.getReturnType(m.desc()).getInternalName() : "";
                made.forEach((k, v) -> {
                    if (k.startsWith(ret + ".")) {
                        v.forEach(l -> addGlobal(k, l)); // returned to the caller: a factory
                    } else {
                        here.addAll(v);
                    }
                });
                int childCtx = here.size() == ctxs.get(st.ctx()).size() ? st.ctx() : ctxOf(Set.copyOf(here));
                for (Call c : calls.getOrDefault(m, Set.of())) {
                    String sink = sinkOf(c);
                    if (sink != null) {
                        if (seenSites.add(sink + "@" + m)) {
                            hits.add(new Hit(sink, m, pathTo(st)));
                        }
                        continue;
                    }
                    for (M t : classTargets.computeIfAbsent(c, GetHandlerSideEffects.this::classDispatch)) {
                        push(new State(t, childCtx), st);
                    }
                    for (String sub : allSubtypes(c.owner())) {
                        String key = sub + "." + c.name();
                        Set<M> ofKey = lambdasOf(key);
                        if (ofKey == null) {
                            continue;
                        }
                        waiting.computeIfAbsent(key, x -> new HashSet<>()).add(st);
                        for (M lam : global.getOrDefault(key, Set.of())) {
                            push(new State(lam, childCtx), st);
                        }
                        for (M lam : here) {
                            if (ofKey.contains(lam)) {
                                push(new State(lam, childCtx), st);
                            }
                        }
                    }
                }
            }
            return hits;
        }

        List<M> pathTo(State s) {
            List<M> path = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                path.add(0, s.m());
                State p = parent.get(s);
                if (p == null || p.equals(s)) {
                    break;
                }
                s = p;
            }
            return path;
        }
    }

    private Map<String, Set<M>> lambdasByKey;

    /** Every lambda of a key in the program (main code), or null when there is none. */
    Set<M> lambdasOf(String key) {
        if (lambdasByKey == null) {
            lambdasByKey = new HashMap<>();
            creates.values().forEach(byKey -> byKey.forEach((k, v) -> lambdasByKey.computeIfAbsent(k, x -> new HashSet<>()).addAll(v)));
            fieldLambdas.values().forEach(byKey -> byKey.forEach((k, v) -> lambdasByKey.computeIfAbsent(k, x -> new HashSet<>()).addAll(v)));
            wiringLambdas.forEach((k, v) -> lambdasByKey.computeIfAbsent(k, x -> new HashSet<>()).addAll(v));
        }
        return lambdasByKey.get(key);
    }

    Set<M> classDispatch(Call c) {
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

    /** The declaring class of a field as written ({@code owner.name}), walking supertypes. */
    String resolveField(String written) {
        int dot = written.lastIndexOf('.');
        String owner = written.substring(0, dot);
        String name = written.substring(dot + 1);
        for (String c : allSupertypes(owner)) {
            if (fieldsOf.getOrDefault(c, Map.of()).containsKey(name)) {
                return c + "." + name;
            }
        }
        return written;
    }

    private void resolveBindings() {
        for (Object[] b : rawBindings) {
            String key = (String) b[0];
            M lam = (M) b[1];
            String target = (String) b[2];
            String iface = key.substring(0, key.lastIndexOf('.'));
            Set<String> fields = new HashSet<>();
            if (target.startsWith("F:")) {
                fields.add(resolveField(target.substring(2)));
            } else if (target.startsWith("C:")) {
                Set<String> ifaceSupers = allSupertypes(iface);
                for (String c : allSupertypes(target.substring(2))) {
                    fieldsOf.getOrDefault(c, Map.of()).forEach((n, d) -> {
                        if (d.startsWith("L") && ifaceSupers.contains(d.substring(1, d.length() - 1))) {
                            fields.add(c + "." + n);
                        }
                    });
                }
            }
            if (fields.isEmpty()) {
                wiringLambdas.computeIfAbsent(key, x -> new HashSet<>()).add(lam);
            }
            for (String f : fields) {
                fieldLambdas.computeIfAbsent(f, x -> new HashMap<>()).computeIfAbsent(key, x -> new HashSet<>()).add(lam);
            }
        }
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
        // lambdas created while wiring are reachable only through the field they are stored in
        g.resolveBindings();
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
            public org.springframework.asm.FieldVisitor visitField(int access, String name, String desc, String sig,
                                                                   Object value) {
                fieldsOf.computeIfAbsent(owner, k -> new HashMap<>()).put(name, desc);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] ex) {
                M self = new M(owner, name, desc);
                List<Object[]> pending = new ArrayList<>();
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

                    private boolean wiring() {
                        return name.equals("<init>") || name.equals("<clinit>") || beanMethods.contains(self)
                                || configClasses.contains(owner);
                    }

                    private void bind(String target) {
                        for (Object[] p : pending) {
                            rawBindings.add(new Object[] {p[0], p[1], target});
                        }
                        pending.clear();
                    }

                    @Override
                    public void visitFieldInsn(int op, String o, String n, String d) {
                        if (op == Opcodes.GETFIELD || op == Opcodes.GETSTATIC) {
                            fieldReads.computeIfAbsent(self, k -> new HashSet<>()).add(o + "." + n);
                        } else if (!pending.isEmpty()) {
                            bind("F:" + o + "." + n);
                        }
                    }

                    @Override
                    public void visitMethodInsn(int op, String o, String n, String d, boolean itf) {
                        if (!o.startsWith("[")) {
                            out.add(new Call(o, n, d));
                        }
                        if (!pending.isEmpty()) {
                            bind("C:" + o);
                        }
                    }

                    @Override
                    public void visitEnd() {
                        bind("U"); // a lambda returned as a bean: injectable anywhere of its type
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                        if (!"java/lang/invoke/LambdaMetafactory".equals(bsm.getOwner()) || args.length < 2
                                || !(args[1] instanceof Handle impl)) {
                            return;
                        }
                        String key = Type.getReturnType(d).getInternalName() + "." + n;
                        M lam = new M(impl.getOwner(), impl.getName(), impl.getDesc());
                        if (wiring()) {
                            pending.add(new Object[] {key, lam});
                        } else {
                            creates.computeIfAbsent(self, k -> new HashMap<>()).computeIfAbsent(key, k -> new HashSet<>())
                                    .add(lam);
                        }
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
