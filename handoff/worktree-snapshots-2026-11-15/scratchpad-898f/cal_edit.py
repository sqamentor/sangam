import pathlib
p = pathlib.Path('WorkingDayCalendar.java')
t = p.read_text(encoding='utf-8')
def sub(old, new, n=1):
    global t
    assert t.count(old) == n, (old, t.count(old))
    t = t.replace(old, new)
sub("import org.sangam.packs.port.BusinessCalendar;\n", "import org.sangam.packs.port.BusinessCalendar;\nimport org.sangam.packs.port.TenantWorkingDays;\n")
sub(""" * Working-day calendar: Saturday and Sunday off, tenant holidays from configuration (gazetted list), office hours
 * 09:30-18:00 in the tenant zone (IST by default, which has no DST; the engine still stores UTC, A-12).
 */""", """ * Working-day calendar: office hours 09:30-18:00 in the tenant zone (the engine still stores UTC, A-12). Which dates
 * are working days, and the zone, come from the tenant's own configuration when a {@link TenantWorkingDays} source
 * answers for the tenant (sangam-server: the profile business calendar, SL-34); otherwise Saturday and Sunday are off,
 * plus holidays added here, in the default zone (IST, which has no DST).
 */""")
sub("""    private final ZoneId defaultZone;

    public WorkingDayCalendar() {
        this(ZoneId.of("Asia/Kolkata"));
    }

    public WorkingDayCalendar(ZoneId defaultZone) {
        this.defaultZone = defaultZone;
    }
""", """    private final ZoneId defaultZone;
    private final TenantWorkingDays tenants;

    public WorkingDayCalendar() {
        this(ZoneId.of("Asia/Kolkata"));
    }

    public WorkingDayCalendar(ZoneId defaultZone) {
        this(defaultZone, TenantWorkingDays.NONE);
    }

    /** A calendar whose working days and zone come from {@code tenants} for every tenant it answers for. */
    public WorkingDayCalendar(ZoneId defaultZone, TenantWorkingDays tenants) {
        this.defaultZone = java.util.Objects.requireNonNull(defaultZone, "defaultZone");
        this.tenants = java.util.Objects.requireNonNull(tenants, "tenants");
    }
""")
sub("""    private ZoneId zone(String tenantId) {
        return zones.getOrDefault(key(tenantId), defaultZone);
    }

    private boolean working(String tenantId, LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY
                && !holidays.getOrDefault(key(tenantId), Set.of()).contains(d);
    }

    @Override
    public boolean isWorkingDay(String tenantId, Instant at) {
        return working(tenantId, at.atZone(zone(tenantId)).toLocalDate());
    }
""", """    /** The tenant's rule, resolved once per calendar operation. */
    private TenantWorkingDays.Days rule(String tenantId) {
        return tenants.of(tenantId).orElseGet(() -> {
            Set<LocalDate> h = holidays.getOrDefault(key(tenantId), Set.of());
            return new TenantWorkingDays.Days(zones.getOrDefault(key(tenantId), defaultZone), d -> {
                DayOfWeek w = d.getDayOfWeek();
                return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY && !h.contains(d);
            });
        });
    }

    @Override
    public boolean isWorkingDay(String tenantId, Instant at) {
        TenantWorkingDays.Days r = rule(tenantId);
        return r.working().test(at.atZone(r.zone()).toLocalDate());
    }
""")
sub("""                Instant base = from.atZone(zone(tenantId)).plus(p).toInstant();""",
    """                Instant base = from.atZone(rule(tenantId).zone()).plus(p).toInstant();""")
sub("""        long n = Long.parseLong(m.group(1));
        ZoneId z = zone(tenantId);
        return switch (m.group(2)) {
            case "D" -> from.plus(Duration.ofDays(n));
            case "H" -> from.plus(Duration.ofHours(n));
            case "M", "MIN" -> from.plus(Duration.ofMinutes(n));
            case "WD" -> addWorkingDays(tenantId, from.atZone(z), n).toInstant();
            case "WH" -> addWorkingMinutes(tenantId, from.atZone(z), n * 60).toInstant();""",
"""        long n = Long.parseLong(m.group(1));
        TenantWorkingDays.Days r = rule(tenantId);
        return switch (m.group(2)) {
            case "D" -> from.plus(Duration.ofDays(n));
            case "H" -> from.plus(Duration.ofHours(n));
            case "M", "MIN" -> from.plus(Duration.ofMinutes(n));
            case "WD" -> addWorkingDays(r, from.atZone(r.zone()), n).toInstant();
            case "WH" -> addWorkingMinutes(r, from.atZone(r.zone()), n * 60).toInstant();""")
sub("""    private ZonedDateTime addWorkingDays(String tenantId, ZonedDateTime start, long days) {""",
    """    private static ZonedDateTime addWorkingDays(TenantWorkingDays.Days r, ZonedDateTime start, long days) {""")
sub("""            if (working(tenantId, d)) {
                added++;""", """            if (r.working().test(d)) {
                added++;""")
sub("""    private ZonedDateTime addWorkingMinutes(String tenantId, ZonedDateTime start, long minutes) {""",
    """    private static ZonedDateTime addWorkingMinutes(TenantWorkingDays.Days r, ZonedDateTime start, long minutes) {""")
sub("""            if (!working(tenantId, d) || !t.toLocalTime().isBefore(CLOSE)) {""",
    """            if (!r.working().test(d) || !t.toLocalTime().isBefore(CLOSE)) {""")
sub("""        ZoneId z = zone(tenantId);
        ZonedDateTime a = from.atZone(z);
        ZonedDateTime b = to.atZone(z);""", """        TenantWorkingDays.Days r = rule(tenantId);
        ZonedDateTime a = from.atZone(r.zone());
        ZonedDateTime b = to.atZone(r.zone());""")
sub("""            if (working(tenantId, d)) {
                LocalTime s""", """            if (r.working().test(d)) {
                LocalTime s""")
p.write_text(t, encoding='utf-8', newline='')
print('ok')
