import pathlib
p = pathlib.Path('PacksWiring.java')
t = p.read_text(encoding='utf-8')
def sub(old, new, n=1):
    global t
    assert t.count(old) == n, (old, t.count(old))
    t = t.replace(old, new)
sub("""            ObjectProvider<org.sangam.core.time.BusinessClock> businessClocks) {""",
"""            ObjectProvider<org.sangam.core.time.BusinessClock> businessClocks,
            ObjectProvider<org.sangam.config.profile.ProfileResolver> profiles) {""")
sub("""            PackRuntime.install(ports);
            LOG.info("PackRuntime installed: audit chain, seats={}, ledgers={}, calendar=packs WorkingDayCalendar",
                    sec != null ? "sangam-security" : "in-memory", clock != null ? "JDBC" : "in-memory");""",
"""            org.sangam.config.profile.ProfileResolver pr = profiles.getIfAvailable();
            String calendarName = env.getProperty("sangam.packs.calendar", "government");
            if (pr != null) {
                // SL-34: pack SLAs count the tenant's own working days (profile calendar: zone, weekend, holidays)
                ports = ports.withCalendar(packCalendar(pr, businessClocks, calendarName));
            } else {
                LOG.warn("No ProfileResolver bean: pack SLAs use the default working-day calendar (no tenant holidays)");
            }
            PackRuntime.install(ports);
            LOG.info("PackRuntime installed: audit chain, seats={}, ledgers={}, calendar={}",
                    sec != null ? "sangam-security" : "in-memory", clock != null ? "JDBC" : "in-memory",
                    pr != null ? "profile calendar '" + calendarName + "'" : "default working days");""")
sub("""    /**
     * Per-tenant pack provisioning through the REST API""",
"""    /** The packs' business calendar over the tenant profile calendar {@code calendarName} in force today. */
    static org.sangam.packs.port.BusinessCalendar packCalendar(org.sangam.config.profile.ProfileResolver profiles,
            ObjectProvider<org.sangam.core.time.BusinessClock> clocks, String calendarName) {
        return new org.sangam.packs.sim.WorkingDayCalendar(ZoneId.of("Asia/Kolkata"),
                new org.sangam.server.bridge.ProfileWorkingDays(tenant -> profiles.resolve(tenant,
                        ConfigWiring.businessClock(clocks).today(tenant)).locale().calendar(calendarName)));
    }

    /**
     * Per-tenant pack provisioning through the REST API""")
p.write_text(t, encoding='utf-8', newline='')
print('ok')
