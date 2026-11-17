// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.arch;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.asm.AnnotationVisitor;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;

/**
 * Round 9 (CodeQL java/csrf-unprotected-request-type, 447 alerts): a GET handler must not reach a write.
 *
 * <p>The check mirrors CodeQL's call graph on the bytecode of every {@code org.sangam} class on this module's test
 * class path (all product modules): a call reaches every override in a subtype, and a call of a functional interface
 * method reaches every lambda and method reference of that interface, wherever it is created. CodeQL's
 * imprecision is the point: one shared {@code Function<Connection, T>} callback means a read reaches every write
 * lambda. Reads therefore run through {@code Transactions.read(ReadWork)} and
 * {@code EngineAdapter.inEngineReadTransaction(ReadWork)}, whose bodies never call a write callback.
 *
 * <p>Sources: methods annotated {@code @GetMapping}, or {@code @RequestMapping} with GET, HEAD or no method. Sinks:
 * {@code Transactions.inTransaction}, {@code EngineAdapter.inEngineTransaction}, and JDBC
 * {@code executeUpdate}/{@code executeLargeUpdate}/{@code executeBatch}.
 */
class GetHandlersNeverWriteTest {

    private static final String GET_MAPPING = "Lorg/springframework/web/bind/annotation/GetMapping;";
    private static final String REQUEST_MAPPING = "Lorg/springframework/web/bind/annotation/RequestMapping;";
    private static final Set<String> WRITE_CALLBACK_OWNERS = Set.of(
            "org/sangam/core/layer/Transactions", "org/sangam/core/api/EngineAdapter");
    private static final Set<String> WRITE_CALLBACK_NAMES = Set.of("inTransaction", "inEngineTransaction");
    private static final Set<String> JDBC_OWNERS = Set.of("java/sql/Statement", "java/sql/PreparedStatement",
            "java/sql/CallableStatement");
    private static final Set<String> JDBC_WRITES = Set.of("executeUpdate", "executeLargeUpdate", "executeBatch",
            "executeLargeBatch");

    /** A method: {@code owner.name desc}. */
    record M(String owner, String name, String desc) {
        @Override
        public String toString() {
            return owner.replace('/', '.') + "." + name;
        }
    }

    static final class Model {
        final Map<String, String> superOf = new HashMap<>();
        final Map<String, List<String>> interfacesOf = new HashMap<>();
        final Map<String, Set<String>> subtypes = new HashMap<>();
        final Set<M> declared = new HashSet<>();
        final Map<M, Set<M>> calls = new HashMap<>();
        /** Functional interface method (iface, name) -> implementing lambda bodies / referenced methods. */
        final Map<String, Set<M>> lambdaImpls = new HashMap<>();
        final Set<M> sources = new LinkedHashSet<>();
        final Set<M> sinkCallers = new HashSet<>();
        private final Map<M, Set<M>> targetCache = new HashMap<>();

        void subtype(String sub, String sup) {
            subtypes.computeIfAbsent(sup, k -> new HashSet<>()).add(sub);
        }

        Set<String> allSubtypes(String t) {
            Set<String> out = new HashSet<>();
            Deque<String> q = new ArrayDeque<>(List.of(t));
            while (!q.isEmpty()) {
                String c = q.pop();
                if (out.add(c)) {
                    q.addAll(subtypes.getOrDefault(c, Set.of()));
                }
            }
            return out;
        }

        Set<String> allSupertypes(String t) {
            Set<String> out = new HashSet<>();
            Deque<String> q = new ArrayDeque<>(List.of(t));
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
        }

        /** Virtual dispatch: the declaration found upwards, every override in a subtype, every lambda of the type. */
        Set<M> targets(M call) {
            return targetCache.computeIfAbsent(call, this::computeTargets);
        }

        private Set<M> computeTargets(M call) {
            Set<M> out = new HashSet<>();
            for (String sup : allSupertypes(call.owner())) {
                M m = new M(sup, call.name(), call.desc());
                if (declared.contains(m)) {
                    out.add(m);
                }
            }
            for (String sub : allSubtypes(call.owner())) {
                M m = new M(sub, call.name(), call.desc());
                if (declared.contains(m)) {
                    out.add(m);
                }
                out.addAll(lambdaImpls.getOrDefault(sub + "." + call.name(), Set.of()));
            }
            return out;
        }
    }

    @Test
    void noGetHandlerReachesAWriteCallbackOrAJdbcWrite() {
        Model model = load();
        assertTrue(model.sources.size() > 300, "GET handlers found: " + model.sources.size());

        // A call of a write callback, through its interface or an implementation type.
        for (Map.Entry<M, Set<M>> e : model.calls.entrySet()) {
            for (M call : e.getValue()) {
                if (WRITE_CALLBACK_NAMES.contains(call.name()) && isWriteCallbackOwner(model, call.owner())) {
                    model.sinkCallers.add(e.getKey());
                }
            }
        }
        // Reverse reachability from the methods that call a sink.
        Map<M, Set<M>> callers = new HashMap<>();
        for (Map.Entry<M, Set<M>> e : model.calls.entrySet()) {
            for (M call : e.getValue()) {
                for (M t : model.targets(call)) {
                    callers.computeIfAbsent(t, k -> new HashSet<>()).add(e.getKey());
                }
            }
        }
        Map<M, M> next = new HashMap<>();
        Deque<M> q = new ArrayDeque<>();
        for (M s : model.sinkCallers) {
            next.put(s, s);
            q.add(s);
        }
        while (!q.isEmpty()) {
            M m = q.pop();
            for (M c : callers.getOrDefault(m, Set.of())) {
                if (!next.containsKey(c)) {
                    next.put(c, m);
                    q.add(c);
                }
            }
        }
        Set<String> bad = new TreeSet<>();
        for (M src : model.sources) {
            // a mapping on an interface method reaches the implementations
            M hit = next.containsKey(src) ? src
                    : model.targets(src).stream().filter(next::containsKey).findFirst().orElse(null);
            if (hit != null) {
                List<String> path = new ArrayList<>();
                if (!hit.equals(src)) {
                    path.add(src.toString());
                }
                M m = hit;
                for (int i = 0; i < 40; i++) {
                    path.add(m.toString());
                    M n = next.get(m);
                    if (n.equals(m)) {
                        break;
                    }
                    m = n;
                }
                bad.add(String.join("\n      -> ", path));
            }
        }
        StringBuilder msg = new StringBuilder(bad.size() + " of " + model.sources.size()
                + " GET handlers reach a write (Transactions.inTransaction, EngineAdapter.inEngineTransaction or a JDBC"
                + " update); read through Transactions.read / inEngineReadTransaction instead:\n");
        bad.stream().limit(60).forEach(p -> msg.append("  ").append(p).append('\n'));
        try {
            Path report = Path.of("target", "get-handlers-write-report.txt");
            Files.createDirectories(report.getParent());
            Files.write(report, bad);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertTrue(bad.isEmpty(), msg.toString());
    }

    // ------------------------------------------------------------------ bytecode

    private static Model load() {
        Model model = new Model();
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            Path p = Path.of(entry);
            try {
                if (Files.isDirectory(p)) {
                    Path root = p.resolve("org/sangam");
                    if (Files.isDirectory(root)) {
                        try (Stream<Path> s = Files.walk(root)) {
                            for (Path f : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".class"))::iterator) {
                                try (InputStream in = Files.newInputStream(f)) {
                                    read(model, in);
                                }
                            }
                        }
                    }
                } else if (entry.endsWith(".jar") && p.getFileName().toString().startsWith("sangam-")) {
                    try (JarFile jar = new JarFile(p.toFile())) {
                        for (JarEntry je : (Iterable<JarEntry>) jar.stream()::iterator) {
                            if (je.getName().startsWith("org/sangam/") && je.getName().endsWith(".class")) {
                                try (InputStream in = jar.getInputStream(je)) {
                                    read(model, in);
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return model;
    }

    private static void read(Model model, InputStream in) throws IOException {
        new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
            String owner;

            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                              String[] interfaces) {
                owner = name;
                if (superName != null) {
                    model.superOf.put(name, superName);
                    model.subtype(name, superName);
                }
                List<String> ifs = interfaces == null ? List.of() : List.of(interfaces);
                model.interfacesOf.put(name, ifs);
                ifs.forEach(i -> model.subtype(name, i));
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] ex) {
                M self = new M(owner, name, desc);
                model.declared.add(self);
                Set<M> out = model.calls.computeIfAbsent(self, k -> new HashSet<>());

                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        if (GET_MAPPING.equals(descriptor)) {
                            model.sources.add(self);
                            return null;
                        }
                        if (!REQUEST_MAPPING.equals(descriptor)) {
                            return null;
                        }
                        Set<String> methods = new HashSet<>();
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitArray(String n) {
                                if (!"method".equals(n)) {
                                    return null;
                                }
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitEnum(String n2, String d, String value) {
                                        methods.add(value);
                                    }
                                };
                            }

                            @Override
                            public void visitEnd() {
                                if (methods.isEmpty() || methods.contains("GET") || methods.contains("HEAD")) {
                                    model.sources.add(self);
                                }
                            }
                        };
                    }

                    @Override
                    public void visitMethodInsn(int op, String o, String n, String d, boolean itf) {
                        if (JDBC_OWNERS.contains(o) && JDBC_WRITES.contains(n)) {
                            model.sinkCallers.add(self);
                            return;
                        }
                        if (!o.startsWith("[")) {
                            out.add(new M(o, n, d));
                        }
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                        if (!"java/lang/invoke/LambdaMetafactory".equals(bsm.getOwner()) || args.length < 2
                                || !(args[1] instanceof Handle impl)) {
                            return;
                        }
                        String iface = Type.getReturnType(d).getInternalName();
                        M target = new M(impl.getOwner(), impl.getName(), impl.getDesc());
                        model.lambdaImpls.computeIfAbsent(iface + "." + n, k -> new HashSet<>()).add(target);
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
    }

    private static boolean isWriteCallbackOwner(Model model, String owner) {
        if (WRITE_CALLBACK_OWNERS.contains(owner)) {
            return true;
        }
        // an implementation called directly by its own type (still the write callback)
        return model.allSupertypes(owner).stream().anyMatch(WRITE_CALLBACK_OWNERS::contains);
    }
}
