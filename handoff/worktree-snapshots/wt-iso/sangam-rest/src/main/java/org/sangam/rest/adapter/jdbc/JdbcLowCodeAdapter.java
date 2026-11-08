// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import static org.sangam.rest.adapter.jdbc.JdbcSupport.invalid;
import static org.sangam.rest.adapter.jdbc.JdbcSupport.payload;
import static org.sangam.rest.adapter.jdbc.JdbcSupport.required;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.lang.reflect.RecordComponent;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.LowCodePort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TaskPort;
import org.sangam.rest.support.DocumentStore;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;

/**
 * Low-code pages (R77), named queries (R25) and DB-backed data objects (R78), persisted in {@code REST_DOCUMENT}:
 * kind {@code lowcode:page} (doc id and doc_key = the page key), {@code lowcode:data-object} (doc_key = the object
 * key) and {@code lowcode:record} (ref_id = the data object key; one document per record). Queries run through the
 * underlying ports, so their tenant and visibility rules apply. Sensitive fields are masked unless the caller holds
 * {@code lowcode.admin}. REST-backed data objects need the R71 connector layer (sangam-integration): {@code 501} here.
 */
public final class JdbcLowCodeAdapter implements LowCodePort {

    private static final Set<String> BLOCK_KINDS = Set.of("heading", "text", "list", "detail", "kpi", "action");
    private static final Set<String> FIELD_TYPES = Set.of("string", "number", "boolean", "date", "reference", "json");
    private static final Set<String> BACKINGS = Set.of("DB", "REST");
    private static final String MASK = "***";

    private final TaskPort tasks;
    private final ProcessPort processes;
    private final AuditPort audit;
    private final RestProperties props;

    private final TxSupport tx;
    private final DocMap<PageModel> pages;
    private final DocMap<DataObjectDef> objects;
    private final DocMap<Map<String, Object>> records;

    public JdbcLowCodeAdapter(DocumentStore store, TaskPort tasks, ProcessPort processes, AuditPort audit,
                              RestProperties props) {
        this.tx = store.tx();
        this.tasks = tasks;
        this.processes = processes;
        this.audit = audit;
        this.props = props;
        this.pages = new DocMap<>(store, "lowcode:page", PageModel.class,
                p -> DocMap.ix(p.key(), null, null, p.state()));
        this.objects = new DocMap<>(store, "lowcode:data-object", DataObjectDef.class,
                d -> DocMap.ix(d.key(), null, null, d.backing()));
        this.records = new DocMap<>(store, "lowcode:record", body -> body,
                r -> DocMap.ix(null, r.get("createdBy") == null ? null : String.valueOf(r.get("createdBy")),
                        String.valueOf(r.get("_dataObject")), null));
    }

    // ------------------------------------------------------------------ pages

    @Override
    public List<PageSummary> pages(RequestContext ctx) {
        return pageBucket(ctx).values().stream()
                .map(p -> new PageSummary(p.id(), p.key(), p.title(), p.version(), p.state()))
                .sorted(Comparator.comparing(PageSummary::key)).toList();
    }

    @Override
    public PageModel page(RequestContext ctx, String key) {
        PageModel p = key == null ? null : pageBucket(ctx).get(key);
        if (p == null) {
            throw ApiProblem.notFound("Page", key);
        }
        return p;
    }

    @Override
    public synchronized PageModel createPage(RequestContext ctx, PageModel in) {
        return tx.inTx(() -> {
            validatePage(in);
            if (pageBucket(ctx).containsKey(in.key())) {
                throw ApiProblem.conflict("DUPLICATE_KEY", "A page with key " + in.key() + " already exists");
            }
            PageModel p = new PageModel(JdbcSupport.newId(), in.key(), in.title(), 1,
                    in.state() == null ? "DRAFT" : in.state(), List.copyOf(in.blocks()));
            pageBucket(ctx).put(p.key(), p);
            audit.record(ctx, JdbcSupport.securityChain(ctx), "PAGE_SAVED",
                    payload("key", p.key(), "version", p.version(), "actor", ctx.actor()));
            return p;
        });
    }

    @Override
    public synchronized PageModel updatePage(RequestContext ctx, String key, PageModel in) {
        return tx.inTx(() -> {
            PageModel cur = page(ctx, key);
            if (in.key() != null && !in.key().equals(key)) {
                throw invalid("key", "the key of a page cannot change");
            }
            if (in.version() != null && in.version() != cur.version()) {
                throw ApiProblem.conflict("REVISION_CONFLICT", "Page " + key + " is at version " + cur.version())
                        .with("currentRevision", cur.version());
            }
            PageModel merged = new PageModel(cur.id(), key, in.title() == null ? cur.title() : in.title(), null,
                    in.state() == null ? cur.state() : in.state(), in.blocks() == null ? cur.blocks() : in.blocks());
            validatePage(merged);
            PageModel p = new PageModel(cur.id(), key, merged.title(), cur.version() + 1, merged.state(),
                    List.copyOf(merged.blocks()));
            pageBucket(ctx).put(key, p);
            audit.record(ctx, JdbcSupport.securityChain(ctx), "PAGE_SAVED",
                    payload("key", p.key(), "version", p.version(), "actor", ctx.actor()));
            return p;
        });
    }

    private static void validatePage(PageModel p) {
        required(p == null ? null : p.key(), "key");
        required(p.title(), "title");
        if (!p.key().matches("[A-Za-z0-9._-]{1,128}")) {
            throw invalid("key", "key must be 1..128 of [A-Za-z0-9._-]");
        }
        if (p.state() != null) {
            JdbcSupport.oneOf(p.state(), "state", Set.of("DRAFT", "ACTIVE", "RETIRED"));
        }
        if (p.blocks() == null) {
            throw invalid("blocks", "blocks is required");
        }
        Set<String> ids = new HashSet<>();
        for (PageBlock b : p.blocks()) {
            required(b.id(), "blocks.id");
            JdbcSupport.oneOf(b.kind(), "blocks.kind", BLOCK_KINDS);
            if (!ids.add(b.id())) {
                throw invalid("blocks.id", "duplicate block id " + b.id());
            }
            if (("list".equals(b.kind()) || "kpi".equals(b.kind())) && b.query() == null && b.dataObject() == null) {
                throw invalid("blocks.query", "block " + b.id() + " needs a query or a dataObject");
            }
            if ("action".equals(b.kind()) && b.processKey() == null) {
                throw invalid("blocks.processKey", "action block " + b.id() + " needs a processKey");
            }
        }
    }

    // ------------------------------------------------------------------ named queries (R25)

    @Override
    public Page<Map<String, Object>> query(RequestContext ctx, String name, Map<String, String> params,
                                           Page.PageRequest page) {
        Map<String, String> p = params == null ? Map.of() : params;
        if ("tasks.mine".equals(name)) {
            TaskPort.TaskFilter f = new TaskPort.TaskFilter(null, true, null, null, p.get("seatId"), null, null,
                    p.get("businessKey"), p.get("processDefinitionKey"), null, null, p.get("sortBy"), p.get("sortOrder"));
            return tasks.list(ctx, f, page).map(this::asMap);
        }
        if ("process-instances".equals(name)) {
            ProcessPort.ProcessFilter f = new ProcessPort.ProcessFilter(p.get("key"), p.get("businessKey"),
                    p.get("state"), null, null, null, null);
            return processes.list(ctx, f, page).map(this::asMap);
        }
        String key = name != null && name.startsWith("data:") ? name.substring(5) : name;
        if (key != null && objectByKey(ctx, key) != null) {
            return records(ctx, key, p.get("q"), page);
        }
        throw new ApiProblem(404, "QUERY_NOT_FOUND", "No named query " + name);
    }

    /** A port record as a JSON-like map (records, lists, maps and times rendered recursively). */
    private Map<String, Object> asMap(Object o) {
        Object v = plain(o);
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), x));
            return out;
        }
        return Map.of("value", v);
    }

    private static Object plain(Object o) {
        if (o == null || o instanceof String || o instanceof Number || o instanceof Boolean) {
            return o;
        }
        if (o instanceof Record r) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (RecordComponent c : r.getClass().getRecordComponents()) {
                try {
                    m.put(c.getName(), plain(c.getAccessor().invoke(r)));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("cannot read " + c.getName(), e);
                }
            }
            return m;
        }
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), plain(x)));
            return out;
        }
        if (o instanceof java.util.Collection<?> c) {
            return c.stream().map(JdbcLowCodeAdapter::plain).toList();
        }
        return String.valueOf(o);
    }

    // ------------------------------------------------------------------ data objects (R78)

    @Override
    public List<DataObjectDef> dataObjects(RequestContext ctx) {
        return objectBucket(ctx).values().stream().sorted(Comparator.comparing(DataObjectDef::key)).toList();
    }

    @Override
    public synchronized DataObjectDef createDataObject(RequestContext ctx, DataObjectDef in) {
        return tx.inTx(() -> {
            validateObject(ctx, in, null);
            if (objectByKey(ctx, in.key()) != null) {
                throw ApiProblem.conflict("DUPLICATE_KEY", "A data object with key " + in.key() + " already exists");
            }
            DataObjectDef d = new DataObjectDef(JdbcSupport.newId(), in.key(), in.name(), in.backing(), in.endpointRef(),
                    1, List.copyOf(in.fields()));
            objectBucket(ctx).put(d.id(), d);
            audit.record(ctx, JdbcSupport.securityChain(ctx), "DATA_OBJECT_SAVED",
                    payload("key", d.key(), "version", d.version(), "backing", d.backing(), "actor", ctx.actor()));
            return d;
        });
    }

    @Override
    public synchronized DataObjectDef updateDataObject(RequestContext ctx, String id, DataObjectDef in) {
        return tx.inTx(() -> {
            DataObjectDef cur = id == null ? null : objectBucket(ctx).get(id);
            if (cur == null) {
                throw ApiProblem.notFound("Data object", id);
            }
            if (in.key() != null && !in.key().equals(cur.key())) {
                throw invalid("key", "the key of a data object cannot change");
            }
            if (in.version() != null && !in.version().equals(cur.version())) {
                throw ApiProblem.conflict("REVISION_CONFLICT", "Data object " + cur.key() + " is at version " + cur.version())
                        .with("currentRevision", cur.version());
            }
            DataObjectDef merged = new DataObjectDef(id, cur.key(), in.name() == null ? cur.name() : in.name(),
                    in.backing() == null ? cur.backing() : in.backing(),
                    in.endpointRef() == null ? cur.endpointRef() : in.endpointRef(), cur.version(),
                    in.fields() == null ? cur.fields() : in.fields());
            validateObject(ctx, merged, cur.key());
            DataObjectDef d = new DataObjectDef(id, cur.key(), merged.name(), merged.backing(), merged.endpointRef(),
                    cur.version() + 1, List.copyOf(merged.fields()));
            objectBucket(ctx).put(id, d);
            audit.record(ctx, JdbcSupport.securityChain(ctx), "DATA_OBJECT_SAVED",
                    payload("key", d.key(), "version", d.version(), "backing", d.backing(), "actor", ctx.actor()));
            return d;
        });
    }

    @Override
    public Page<Map<String, Object>> records(RequestContext ctx, String key, String q, Page.PageRequest page) {
        DataObjectDef d = requireDb(ctx, key);
        boolean unmask = ctx.inGroup(Permissions.GROUP_ADMIN) || ctx.inGroup(Permissions.GROUP_TENANT_ADMIN)
                || ctx.inGroup(Permissions.GROUP_MODEL_AUTHOR);
        String needle = q == null ? null : q.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> all = recordBucket(ctx, key).stream()
                .filter(r -> needle == null || r.values().stream()
                        .anyMatch(v -> v != null && String.valueOf(v).toLowerCase(Locale.ROOT).contains(needle)))
                .map(r -> unmask ? r : mask(d, r)).toList();
        return Page.slice(all, page, props.getCountCap());
    }

    @Override
    public Map<String, Object> createRecord(RequestContext ctx, String key, Map<String, Object> record) {
        return tx.inTx(() -> {
            DataObjectDef d = requireDb(ctx, key);
            Map<String, Object> r = new LinkedHashMap<>();
            for (DataObjectField f : d.fields()) {
                Object v = record == null ? null : record.get(f.name());
                if (v == null) {
                    if (f.required()) {
                        throw invalid(f.name(), f.name() + " is required");
                    }
                    continue;
                }
                r.put(f.name(), checkType(ctx, f, v));
            }
            if (record != null) {
                for (String k : record.keySet()) {
                    if (d.fields().stream().noneMatch(f -> f.name().equals(k)) && !"id".equals(k)) {
                        throw invalid(k, "unknown field " + k + " for data object " + key);
                    }
                }
            }
            r.put("id", JdbcSupport.newId());
            r.put("createdBy", ctx.principalId());
            r.put("createdAt", Instant.now().toString());
            Map<String, Object> stored = new LinkedHashMap<>(r);
            stored.put("_dataObject", key);
            records.in(JdbcSupport.tenant(ctx)).put(String.valueOf(r.get("id")), stored);
            audit.record(ctx, "data:" + key, "DATA_OBJECT_RECORD_CREATED",
                    payload("dataObject", key, "recordId", r.get("id"), "actor", ctx.actor()));
            return r;
        });
    }

    private Object checkType(RequestContext ctx, DataObjectField f, Object v) {
        switch (f.type()) {
            case "string" -> {
                return String.valueOf(v);
            }
            case "number" -> {
                try {
                    // R33: numbers are kept exact, as decimal strings.
                    return new BigDecimal(String.valueOf(v)).toPlainString();
                } catch (NumberFormatException e) {
                    throw invalid(f.name(), f.name() + " must be a number");
                }
            }
            case "boolean" -> {
                if (v instanceof Boolean b) {
                    return b;
                }
                String s = String.valueOf(v);
                if (!s.equals("true") && !s.equals("false")) {
                    throw invalid(f.name(), f.name() + " must be true or false");
                }
                return Boolean.parseBoolean(s);
            }
            case "date" -> {
                try {
                    return LocalDate.parse(String.valueOf(v)).toString();
                } catch (DateTimeParseException e) {
                    throw invalid(f.name(), f.name() + " must be an ISO date (yyyy-MM-dd)");
                }
            }
            case "reference" -> {
                String id = String.valueOf(v);
                boolean exists = recordBucket(ctx, f.reference()).stream().anyMatch(r -> id.equals(r.get("id")));
                if (!exists) {
                    throw invalid(f.name(), f.name() + " references an unknown " + f.reference() + " record " + id);
                }
                return id;
            }
            default -> {
                return v;
            }
        }
    }

    private static Map<String, Object> mask(DataObjectDef d, Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        for (DataObjectField f : d.fields()) {
            if (f.sensitive() && m.containsKey(f.name())) {
                m.put(f.name(), MASK);
            }
        }
        return m;
    }

    private void validateObject(RequestContext ctx, DataObjectDef d, String selfKey) {
        required(d == null ? null : d.key(), "key");
        required(d.name(), "name");
        JdbcSupport.oneOf(d.backing(), "backing", BACKINGS);
        if ("REST".equals(d.backing())) {
            required(d.endpointRef(), "endpointRef");
        }
        if (!d.key().matches("[A-Za-z0-9._-]{1,128}")) {
            throw invalid("key", "key must be 1..128 of [A-Za-z0-9._-]");
        }
        if (d.fields() == null || d.fields().isEmpty()) {
            throw invalid("fields", "a data object needs at least one field");
        }
        Set<String> names = new HashSet<>();
        for (DataObjectField f : d.fields()) {
            required(f.name(), "fields.name");
            JdbcSupport.oneOf(f.type(), "fields.type", FIELD_TYPES);
            if (!names.add(f.name())) {
                throw invalid("fields.name", "duplicate field " + f.name());
            }
            if ("id".equals(f.name())) {
                throw invalid("fields.name", "id is reserved");
            }
            if ("reference".equals(f.type())) {
                required(f.reference(), "fields.reference");
                if (!f.reference().equals(selfKey == null ? d.key() : selfKey) && objectByKey(ctx, f.reference()) == null) {
                    throw invalid("fields.reference", "field " + f.name() + " references unknown data object " + f.reference());
                }
            }
        }
    }

    private DataObjectDef requireDb(RequestContext ctx, String key) {
        DataObjectDef d = objectByKey(ctx, key);
        if (d == null) {
            throw ApiProblem.notFound("Data object", key);
        }
        if ("REST".equals(d.backing())) {
            throw ApiProblem.notInstalled("REST-backed data objects (R78)", "sangam-integration");
        }
        return d;
    }

    private DataObjectDef objectByKey(RequestContext ctx, String key) {
        if (key == null) {
            return null;
        }
        return objectBucket(ctx).where(DocumentStore.Filter.key(key)).stream().findFirst().orElse(null);
    }

    private DocMap<PageModel>.View pageBucket(RequestContext ctx) {
        return pages.in(JdbcSupport.tenant(ctx));
    }

    private DocMap<DataObjectDef>.View objectBucket(RequestContext ctx) {
        return objects.in(JdbcSupport.tenant(ctx));
    }

    /** The records of one data object, oldest first; the internal {@code _dataObject} tag is not returned. */
    private List<Map<String, Object>> recordBucket(RequestContext ctx, String key) {
        return records.in(JdbcSupport.tenant(ctx)).where(DocumentStore.Filter.ref(key)).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.remove("_dataObject");
            return Collections.unmodifiableMap(m);
        }).toList();
    }
}
