import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
pairs = [
    ('''import org.sangam.core.quota.TenantQuotas;
''', '''import org.sangam.core.quota.TenantQuotas;
import org.sangam.core.security.SecurityIdentity;
'''),
    ('''import java.util.concurrent.ConcurrentHashMap;
''', '''import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
'''),
    ('''    private final Map<String, Sink> sinks = new ConcurrentHashMap<>();
''', '''    private final Map<String, Sink> sinks = new ConcurrentHashMap<>();
    private volatile Supplier<Optional<SecurityIdentity>> boundIdentity = Optional::empty;
'''),
    ('''    public Optional<Sink> sink(String objectType) {''', '''    /**
     * The identity bound to the creating request (the server binds the security context), snapshotted for jobs of an
     * {@link IdentitySink} (M-130).
     */
    public BulkIngest creatorIdentity(Supplier<Optional<SecurityIdentity>> bound) {
        this.boundIdentity = Objects.requireNonNull(bound);
        return this;
    }

    public Optional<Sink> sink(String objectType) {'''),
    ('''    public Job create(String tenant, String objectType, Integer expectedChunks, String actor) {
        requireTenant(tenant);
        if (sink(objectType).isEmpty()) {
            throw new BulkException(BulkException.Kind.INVALID, "UNKNOWN_OBJECT_TYPE", "no bulk sink for object type " + objectType);
        }
''', '''    public Job create(String tenant, String objectType, Integer expectedChunks, String actor) {
        return create(tenant, objectType, expectedChunks, actor, null);
    }

    /** A job created by {@code creator} outside a request (its snapshot is kept for an {@link IdentitySink}). */
    public Job create(String tenant, String objectType, Integer expectedChunks, SecurityIdentity creator) {
        return create(tenant, objectType, expectedChunks, creator.personId(), CreatorSnapshot.requireNotSystem(creator));
    }

    private Job create(String tenant, String objectType, Integer expectedChunks, String actor, SecurityIdentity creator) {
        requireTenant(tenant);
        if (sink(objectType).isEmpty()) {
            throw new BulkException(BulkException.Kind.INVALID, "UNKNOWN_OBJECT_TYPE", "no bulk sink for object type " + objectType);
        }
        String snapshot = !(sinks.get(objectType) instanceof IdentitySink) ? null : CreatorSnapshot.write(creator != null
                ? CreatorSnapshot.forJob(Optional.of(creator), tenant, actor) : CreatorSnapshot.forJob(boundIdentity.get(), tenant, actor));
'''),
    ('''                            + " created_by, created_at, updated_at, closed_at, completed_at) VALUES (?,?,?,'OPEN',?,0,0,?,"
                            + dialect.now() + "," + dialect.now() + ",NULL,NULL)",
                    id, tenant, objectType, expectedChunks, actor);''', '''                            + " created_by, creator_identity, created_at, updated_at, closed_at, completed_at) VALUES (?,?,?,'OPEN',?,0,0,?,?,"
                            + dialect.now() + "," + dialect.now() + ",NULL,NULL)",
                    id, tenant, objectType, expectedChunks, actor, snapshot);'''),
    ('''                Object[] job = Jdbc.one(c, "SELECT tenant_id, object_type, state, created_by FROM OPS_BULK_JOB WHERE id = ?",
                        rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)}, jobId).orElseThrow();''',
     '''                Object[] job = Jdbc.one(c, "SELECT tenant_id, object_type, state, created_by, creator_identity FROM OPS_BULK_JOB WHERE id = ?",
                        rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)}, jobId).orElseThrow();'''),
    ('''                String ref = sink.apply(c, tenant, (String) job[3], ext, readMap((String) row.get()[3]), key.map(k -> (String) k[1]).orElse(null));''',
     '''                String existingRef = key.map(k -> (String) k[1]).orElse(null);
                String ref = sink instanceof IdentitySink is // M-130: as the creator, so the target re-checks per object
                        ? is.apply(c, CreatorSnapshot.forRow((String) job[4], tenant), ext, readMap((String) row.get()[3]), existingRef)
                        : sink.apply(c, tenant, (String) job[3], ext, readMap((String) row.get()[3]), existingRef);'''),
]
for old, new in pairs:
    assert s.count(old) == 1, old[:80]
    s = s.replace(old, new)
open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
