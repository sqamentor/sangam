import os
import sys

os.chdir(sys.argv[1])


def edit(p, pairs):
    s = open(p, encoding='utf-8').read()
    for o, n in pairs:
        assert s.count(o) == 1, (p, o, s.count(o))
        s = s.replace(o, n)
    open(p, 'w', encoding='utf-8', newline='').write(s)


edit('sangam-ops/src/main/java/org/sangam/ops/utc/UtcDataset.java', [
    (''' * @param tenantId         restricts the dataset to rows with this {@code tenant_id}, or null for every row
 */
public record UtcDataset(String table, List<String> columns, String provenanceColumn, ZoneId sourceZone,
                         Instant cutover, String tenantId) {
''', ''' * @param tenantId         restricts the dataset to rows with this {@code tenant_id}, or null for every row
 * @param sessionZone      owner decision 240: the historical MySQL/MariaDB session time zone under which the
 *                         session-zoned engine columns of the table were written (named zone or whole-minute offset), or
 *                         null; those columns are refused on MySQL without it
 */
public record UtcDataset(String table, List<String> columns, String provenanceColumn, ZoneId sourceZone,
                         Instant cutover, String tenantId, ZoneId sessionZone) {

    /** A dataset without a declared session zone. */
    public UtcDataset(String table, List<String> columns, String provenanceColumn, ZoneId sourceZone, Instant cutover,
                      String tenantId) {
        this(table, columns, provenanceColumn, sourceZone, cutover, tenantId, null);
    }
'''),
    ('''            throw new IllegalArgumentException("tenantId is null (every row) or a tenant id, never blank");
        }
''', '''            throw new IllegalArgumentException("tenantId is null (every row) or a tenant id, never blank");
        }
        SessionZones.requireDeclarable(sessionZone);
'''),
    ('''                cutover.toString(), tenantId == null ? "" : tenantId, rulesVersion(sourceZone));''',
     '''                cutover.toString(), tenantId == null ? "" : tenantId, rulesVersion(sourceZone))
                // decision 240: only a declared session zone enters, so earlier runs keep their fingerprint
                + (sessionZone == null ? "" : "\\u0000session=" + SessionZones.text(sessionZone) + "\\u0000"
                + rulesVersion(sessionZone));'''),
])
print('ok')
