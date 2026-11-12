// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.enterprise;

import org.sangam.channels.enterprise.SapRfc.Failure;
import org.sangam.channels.enterprise.SapRfc.RfcException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * {@link SapRfc.Client} over the customer's SAP JCo 3, loaded by reflection only. No JCo class is referenced at compile
 * time, so the product builds, ships and starts without it; the first call looks for {@code com.sap.conn.jco.JCoDestinationManager}
 * on the context class loader (then this class's loader) and fails with {@link Failure#NOT_AVAILABLE} when it or its native
 * library is missing. Methods are looked up on JCo's public interfaces, never on its implementation classes.
 *
 * <p>JCo allows one destination data provider per JVM, so this class registers a single provider (a dynamic proxy of
 * {@code com.sap.conn.jco.ext.DestinationDataProvider}) that serves every destination registered here; a changed setting
 * or password is announced to JCo through its event listener, so a rotated password takes effect without a restart.
 */
public final class JcoRfcClient implements SapRfc.Client {

    static final String MANAGER = "com.sap.conn.jco.JCoDestinationManager";
    private static final String PKG = "com.sap.conn.jco.";

    /** The process-wide provider state (JCo's own registry is process-wide too). */
    private static final Map<String, Properties> DESTINATIONS = new ConcurrentHashMap<>();
    private static volatile Object listener;
    private static volatile boolean registered;

    private final ClassLoader loader;

    public JcoRfcClient() {
        this(null);
    }

    /** {@code loader} null means the thread context class loader, then this class's own loader. */
    public JcoRfcClient(ClassLoader loader) {
        this.loader = loader;
    }

    /** True when JCo can be loaded (for the operator status view); never throws. */
    public boolean available() {
        try {
            type("JCoDestinationManager");
            return true;
        } catch (RfcException e) {
            return false;
        }
    }

    @Override
    public SapRfc.Result call(SapRfc.Destination d, String password, SapRfc.Call call, Predicate<SapRfc.Result> commitIf) throws RfcException {
        Class<?> manager = type("JCoDestinationManager");
        register(d, password);
        Object dest = invokeStatic(manager, "getDestination", new Class<?>[]{String.class}, d.name());
        Class<?> destType = type("JCoDestination");
        Class<?> context = type("JCoContext");
        boolean stateful = call.commit();
        if (stateful) {
            invokeStatic(context, "begin", new Class<?>[]{destType}, dest);
        }
        try {
            Object fn = function(dest, call.function());
            fill(param(fn, "getImportParameterList"), call.imports(), call.function() + " imports");
            fill(param(fn, "getChangingParameterList"), call.changing(), call.function() + " changing");
            Object tableList = param(fn, "getTableParameterList");
            for (Map.Entry<String, List<Map<String, Object>>> t : call.tables().entrySet()) {
                if (tableList == null) {
                    throw new RfcException(Failure.OTHER, null, call.function() + " has no table parameters", null);
                }
                Object table = invoke(type("JCoRecord"), tableList, "getTable", new Class<?>[]{String.class}, t.getKey());
                for (Map<String, Object> row : t.getValue()) {
                    invoke(type("JCoTable"), table, "appendRow", new Class<?>[0]);
                    for (Map.Entry<String, Object> f : row.entrySet()) {
                        invoke(type("JCoRecord"), table, "setValue", new Class<?>[]{String.class, Object.class}, f.getKey(), f.getValue());
                    }
                }
            }
            invoke(type("JCoFunction"), fn, "execute", new Class<?>[]{destType}, dest);
            SapRfc.Result result = new SapRfc.Result(record(param(fn, "getExportParameterList")), record(param(fn, "getChangingParameterList")),
                    tables(param(fn, "getTableParameterList")));
            if (stateful) {
                boolean commit = commitIf.test(result);
                Object end = function(dest, commit ? "BAPI_TRANSACTION_COMMIT" : "BAPI_TRANSACTION_ROLLBACK");
                Object imports = param(end, "getImportParameterList");
                if (imports != null && commit) {
                    invoke(type("JCoRecord"), imports, "setValue", new Class<?>[]{String.class, Object.class}, "WAIT", "X");
                }
                invoke(type("JCoFunction"), end, "execute", new Class<?>[]{destType}, dest);
                if (commit) {
                    // WAIT=X: an update-task failure is reported in the commit's RETURN; whether anything was written is not known
                    String error = SapRfc.firstError(new SapRfc.Result(record(param(end, "getExportParameterList")), Map.of(), Map.of()));
                    if (error != null) {
                        throw new RfcException(Failure.SYSTEM_FAILURE, null, "BAPI_TRANSACTION_COMMIT reported " + error, null);
                    }
                }
            }
            return result;
        } finally {
            if (stateful) {
                try {
                    invokeStatic(context, "end", new Class<?>[]{destType}, dest);
                } catch (RfcException e) {
                    org.slf4j.LoggerFactory.getLogger(JcoRfcClient.class).warn("JCoContext.end failed for {}: {}", d.name(), e.getMessage());
                }
            }
        }
    }

    private Object function(Object dest, String name) throws RfcException {
        Object repo = invoke(type("JCoDestination"), dest, "getRepository", new Class<?>[0]);
        Object fn = invoke(type("JCoRepository"), repo, "getFunction", new Class<?>[]{String.class}, name);
        if (fn == null) {
            throw new RfcException(Failure.FUNCTION_NOT_FOUND, null, "function module " + name + " not found in the repository", null);
        }
        return fn;
    }

    private Object param(Object fn, String getter) throws RfcException {
        return invoke(type("JCoFunction"), fn, getter, new Class<?>[0]);
    }

    @SuppressWarnings("unchecked")
    private void fill(Object list, Map<String, Object> values, String what) throws RfcException {
        if (values.isEmpty()) {
            return;
        }
        if (list == null) {
            throw new RfcException(Failure.OTHER, null, what + ": the function has no such parameters", null);
        }
        Class<?> rec = type("JCoRecord");
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> m) {
                Object structure = invoke(rec, list, "getStructure", new Class<?>[]{String.class}, e.getKey());
                for (Map.Entry<String, Object> f : ((Map<String, Object>) m).entrySet()) {
                    invoke(rec, structure, "setValue", new Class<?>[]{String.class, Object.class}, f.getKey(), f.getValue());
                }
            } else {
                invoke(rec, list, "setValue", new Class<?>[]{String.class, Object.class}, e.getKey(), e.getValue());
            }
        }
    }

    /** A parameter list, structure or table row as a map; nested structures and tables are converted too. */
    private Map<String, Object> record(Object rec) throws RfcException {
        Map<String, Object> out = new LinkedHashMap<>();
        if (rec == null) {
            return out;
        }
        Class<?> recType = type("JCoRecord");
        Object meta = invoke(recType, rec, "getMetaData", new Class<?>[0]);
        Class<?> metaType = type("JCoMetaData");
        int n = (Integer) invoke(metaType, meta, "getFieldCount", new Class<?>[0]);
        for (int i = 0; i < n; i++) {
            String name = (String) invoke(metaType, meta, "getName", new Class<?>[]{int.class}, i);
            out.put(name, value(invoke(recType, rec, "getValue", new Class<?>[]{int.class}, i)));
        }
        return out;
    }

    private Map<String, List<Map<String, Object>>> tables(Object list) throws RfcException {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        if (list == null) {
            return out;
        }
        Map<String, Object> all = record(list);
        for (Map.Entry<String, Object> e : all.entrySet()) {
            if (e.getValue() instanceof List<?> rows) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> typed = (List<Map<String, Object>>) rows;
                out.put(e.getKey(), typed);
            }
        }
        return out;
    }

    private Object value(Object v) throws RfcException {
        if (v == null) {
            return "";
        }
        if (type("JCoTable").isInstance(v)) {
            Class<?> t = type("JCoTable");
            int rows = (Integer) invoke(t, v, "getNumRows", new Class<?>[0]);
            List<Map<String, Object>> out = new ArrayList<>(rows);
            for (int i = 0; i < rows; i++) {
                invoke(t, v, "setRow", new Class<?>[]{int.class}, i);
                out.add(record(v));
            }
            return out;
        }
        if (type("JCoStructure").isInstance(v)) {
            return record(v);
        }
        if (v instanceof BigDecimal b) {
            return b.toPlainString();
        }
        if (v instanceof Date date) {
            return date.toInstant().toString();
        }
        if (v instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        return v;
    }

    // ---- destination data provider ---------------------------------------------------------------

    private void register(SapRfc.Destination d, String password) throws RfcException {
        Properties p = new Properties();
        d.properties().forEach((k, v) -> {
            if (k.startsWith("jco.client.") || k.startsWith("jco.destination.")) {
                p.setProperty(k, v);
            }
        });
        if (d.properties().containsKey("jco.client.mshost")) {
            p.remove("jco.client.ashost");
        } else {
            p.setProperty("jco.client.ashost", d.host());
            p.setProperty("jco.client.sysnr", d.systemNumber());
        }
        p.setProperty("jco.client.client", d.client());
        p.setProperty("jco.client.user", d.user());
        p.setProperty("jco.client.passwd", password);
        p.setProperty("jco.client.lang", d.language() == null || d.language().isBlank() ? "EN" : d.language());
        Properties before = DESTINATIONS.put(d.name(), p);
        ensureProvider();
        if (before != null && !before.equals(p) && listener != null) {
            invoke(type("ext.DestinationDataEventListener"), listener, "updated", new Class<?>[]{String.class}, d.name());
        }
    }

    private void ensureProvider() throws RfcException {
        if (registered) {
            return;
        }
        synchronized (JcoRfcClient.class) {
            if (registered) {
                return;
            }
            Class<?> provider = type("ext.DestinationDataProvider");
            InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
                case "getDestinationProperties" -> {
                    Properties p = DESTINATIONS.get((String) args[0]);
                    yield p == null ? null : (Properties) p.clone();
                }
                case "supportsEvents" -> true;
                case "setDestinationDataEventListener" -> {
                    listener = args[0];
                    yield null;
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "SangamDestinationDataProvider";
                default -> throw new UnsupportedOperationException(method.getName());
            };
            Object proxy = Proxy.newProxyInstance(provider.getClassLoader(), new Class<?>[]{provider}, h);
            invokeStatic(type("ext.Environment"), "registerDestinationDataProvider", new Class<?>[]{provider}, proxy);
            registered = true;
        }
    }

    // ---- reflection ------------------------------------------------------------------------------

    private Class<?> type(String simpleName) throws RfcException {
        String name = PKG + simpleName;
        List<ClassLoader> loaders = new ArrayList<>();
        if (loader != null) {
            loaders.add(loader);
        } else {
            ClassLoader ctx = Thread.currentThread().getContextClassLoader();
            if (ctx != null) {
                loaders.add(ctx);
            }
            loaders.add(JcoRfcClient.class.getClassLoader());
        }
        Throwable last = null;
        for (ClassLoader l : loaders) {
            try {
                return Class.forName(name, true, l);
            } catch (ClassNotFoundException e) {
                last = e;
            } catch (LinkageError e) {
                // UnsatisfiedLinkError or ExceptionInInitializerError: the jar is there, its native library is not
                throw new RfcException(Failure.NOT_AVAILABLE, null, "SAP JCo is present but cannot start (" + e + "). Put the sapjco3 native"
                        + " library on java.library.path next to sapjco3.jar.", e);
            }
        }
        throw new RfcException(Failure.NOT_AVAILABLE, null, "SAP JCo (sapjco3.jar) is not installed. Sangam does not ship it: obtain it from SAP"
                + " under your licence and put sapjco3.jar in /opt/sangam/drivers (loader.path) and its native library on java.library.path.", last);
    }

    private Object invokeStatic(Class<?> owner, String method, Class<?>[] types, Object... args) throws RfcException {
        return invoke(owner, null, method, types, args);
    }

    private Object invoke(Class<?> owner, Object target, String method, Class<?>[] types, Object... args) throws RfcException {
        Method m;
        try {
            m = owner.getMethod(method, types);
        } catch (NoSuchMethodException e) {
            throw new RfcException(Failure.NOT_AVAILABLE, null, "this SAP JCo has no " + owner.getSimpleName() + "." + method + "; JCo 3 is required", e);
        }
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw translate(e.getCause() == null ? e : e.getCause());
        } catch (IllegalAccessException e) {
            throw new RfcException(Failure.OTHER, null, "cannot call " + owner.getSimpleName() + "." + method + ": " + e.getMessage(), e);
        }
    }

    /** Maps a JCo exception by its documented error-group constants, read by name (no numbers are assumed). */
    private RfcException translate(Throwable t) {
        try {
            Class<?> jcoEx = type("JCoException");
            if (type("AbapException").isInstance(t)) {
                return new RfcException(Failure.ABAP_EXCEPTION, String.valueOf(jcoEx.getMethod("getKey").invoke(t)), t.getMessage(), t);
            }
            if (jcoEx.isInstance(t)) {
                int group = (Integer) jcoEx.getMethod("getGroup").invoke(t);
                Failure f = group(jcoEx, "JCO_ERROR_LOGON_FAILURE", group) ? Failure.LOGON
                        : group(jcoEx, "JCO_ERROR_COMMUNICATION", group) ? Failure.COMMUNICATION
                        : group(jcoEx, "JCO_ERROR_SYSTEM_FAILURE", group) ? Failure.SYSTEM_FAILURE
                        : group(jcoEx, "JCO_ERROR_ABAP_EXCEPTION", group) ? Failure.ABAP_EXCEPTION
                        : group(jcoEx, "JCO_ERROR_FUNCTION_NOT_FOUND", group) ? Failure.FUNCTION_NOT_FOUND : Failure.OTHER;
                Object key = jcoEx.getMethod("getKey").invoke(t);
                return new RfcException(f, key == null ? null : key.toString(), t.getMessage(), t);
            }
        } catch (RfcException | ReflectiveOperationException | RuntimeException e) {
            // fall through: report the original problem
        }
        return new RfcException(Failure.OTHER, null, Objects.toString(t.getMessage(), t.toString()), t);
    }

    private static boolean group(Class<?> jcoEx, String constant, int group) {
        try {
            return jcoEx.getField(constant).getInt(null) == group;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
