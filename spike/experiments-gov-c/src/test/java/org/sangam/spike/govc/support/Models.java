package org.sangam.spike.govc.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the shared models of {@code src/test/resources/models}. One file per model, used by BOTH engines. The only
 * engine-specific parts are the vendor tokens below; each is expanded per engine at load time and listed in the
 * scenario's JSON ("variants"), so the variant surface is exactly the token list. Everything else in the XML is
 * standard BPMN 2.0.2 and byte-identical on both engines.
 *
 * <ul>
 *   <li>{@code @@ext|topic@@} (attribute of a serviceTask): external work, same reason as corpus V-02.
 *       {@code @@ext|topic;nonexclusive@@}: on Flowable also {@code flowable:exclusive="false"} (several external jobs of
 *       one instance must be acquirable together, e.g. a parallel multi-instance); nothing on Operaton.</li>
 *   <li>{@code @@expr|${...}@@} (attribute of a serviceTask): an expression service task (BPMN has no standard way to
 *       execute an expression; used for counters/aggregates only).</li>
 *   <li>{@code @@callio|in=a,b;out=c,d@@} (first child of a callActivity): variable mapping in/out of a called
 *       process (BPMN 2.0.2 callActivity has no executable variable mapping).</li>
 *   <li>{@code @@callbk@@} (attribute of a callActivity) together with the flag {@code bk} in callio: the child gets
 *       the parent's business key (Operaton: an in-mapping element; Flowable: an attribute).</li>
 * </ul>
 */
public final class Models {

    /** Per-engine expansion of the tokens. */
    public interface Dialect {
        String namespace();

        String ext(String topic);

        String expr(String expression);

        String callIo(List<String> in, List<String> out, boolean businessKey);

        String callBusinessKey();
    }

    public record Loaded(Map<String, byte[]> resources, List<String> variants) {
    }

    private static final Pattern TOKEN = Pattern.compile("@@(ext|expr|callio|callbk)(?:\\|(.*?))?@@", Pattern.DOTALL);

    private Models() {
    }

    public static String raw(String name) {
        try (InputStream in = Models.class.getResourceAsStream("/models/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("no model " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Loads and expands several model files for one deployment. */
    public static Loaded load(Dialect d, String... names) {
        Map<String, byte[]> res = new LinkedHashMap<>();
        Set<String> variants = new LinkedHashSet<>();
        for (String n : names) {
            String xml = raw(n);
            Matcher m = TOKEN.matcher(xml);
            StringBuilder sb = new StringBuilder();
            boolean any = false;
            while (m.find()) {
                any = true;
                String kind = m.group(1);
                String arg = m.group(2) == null ? "" : m.group(2);
                String rep = switch (kind) {
                    case "ext" -> d.ext(arg);
                    case "expr" -> d.expr(arg);
                    case "callio" -> d.callIo(list(arg, "in"), list(arg, "out"), (";" + arg + ";").contains(";bk;"));
                    case "callbk" -> d.callBusinessKey();
                    default -> throw new IllegalStateException(kind);
                };
                variants.add(n + ": " + kind + (arg.isEmpty() ? "" : "(" + arg + ")"));
                m.appendReplacement(sb, Matcher.quoteReplacement(rep));
            }
            m.appendTail(sb);
            String out = sb.toString();
            if (any) {
                out = out.replaceFirst("<definitions ", "<definitions " + d.namespace() + " ");
            }
            res.put(n, out.getBytes(StandardCharsets.UTF_8));
        }
        return new Loaded(res, new ArrayList<>(variants));
    }

    private static List<String> list(String arg, String key) {
        for (String part : arg.split(";")) {
            String p = part.trim();
            if (p.startsWith(key + "=")) {
                List<String> out = new ArrayList<>();
                for (String v : p.substring(key.length() + 1).split(",")) {
                    if (!v.isBlank()) {
                        out.add(v.trim());
                    }
                }
                return out;
            }
        }
        return List.of();
    }

    /** XML attribute escaping for expressions placed inside attributes. */
    public static String attr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
