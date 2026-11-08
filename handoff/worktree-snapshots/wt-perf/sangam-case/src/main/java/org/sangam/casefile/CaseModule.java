// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile;

import org.sangam.casefile.common.CaseEvents;
import org.sangam.casefile.common.NumberService;
import org.sangam.casefile.config.CaseConfigRegistry;
import org.sangam.casefile.deadline.DeadlineService;
import org.sangam.casefile.file.CaseContentsService;
import org.sangam.casefile.file.CaseFileService;
import org.sangam.casefile.file.CaseLinkService;
import org.sangam.casefile.file.CaseTimelineService;
import org.sangam.casefile.file.HearingService;
import org.sangam.casefile.file.PartyService;
import org.sangam.casefile.meeting.ActionItemService;
import org.sangam.casefile.meeting.CommitteeService;
import org.sangam.casefile.meeting.MeetingService;
import org.sangam.casefile.ports.CaseFilePorts;
import org.sangam.casefile.reference.ReferenceService;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Transactions;
import org.sangam.ports.content.ContentService;
import org.sangam.ports.links.InstanceLinkService;

import java.time.ZoneId;
import java.util.Objects;

/**
 * Wiring of the case module for one engine database, and the holder the BPMN delegates and the CMMN listener use
 * ({@link #install}/{@link #get}, one engine per JVM, ARCHITECTURE §1.6). Build it in sangam-server with the engine's
 * {@code Transactions} ({@code EngineTx.transactions(engine)}, so product rows commit with engine state, Q1).
 *
 * <pre>{@code
 * CaseModule.Settings s = new CaseModule.Settings(dialect, EngineTx.transactions(engine));
 * s.outbox = outbox; s.links = instanceLinkService; s.content = contentService;
 * s.notifier = ...; s.calendars = name -> workCalendars.get(name); s.escalationSeats = ...;
 * CaseModule.install(new CaseModule(s));
 * cfg.getCustomPostCmmnTransformListeners().add(new CaseFileCmmnListener(true));
 * }</pre>
 */
public final class CaseModule {

    /** Mutable settings bag; only {@code dialect} and {@code tx} are required. */
    public static final class Settings {
        public final Dialect dialect;
        public final Transactions tx;
        public Outbox outbox;
        public InstanceLinkService links;
        public ContentService content;
        public ZoneId zone = ZoneId.of("UTC");
        public CaseFilePorts.Notifier notifier = CaseFilePorts.Notifier.NONE;
        public CaseFilePorts.Calendars calendars = CaseFilePorts.Calendars.PLAIN;
        public CaseFilePorts.VenueBooking venues = CaseFilePorts.VenueBooking.NONE;
        public CaseFilePorts.PartyDirectory partyDirectory = CaseFilePorts.PartyDirectory.NONE;
        public CaseFilePorts.ProcessHistorySource history = CaseFilePorts.ProcessHistorySource.NONE;
        public CaseFilePorts.ProcessStarter processes = CaseFilePorts.ProcessStarter.NONE;
        public CaseFilePorts.EscalationSeats escalationSeats = CaseFilePorts.EscalationSeats.NONE;

        public Settings(Dialect dialect, Transactions tx) {
            this.dialect = Objects.requireNonNull(dialect, "dialect");
            this.tx = Objects.requireNonNull(tx, "tx");
        }
    }

    private static volatile CaseModule installed;

    private final Settings settings;
    private final CaseConfigRegistry config;
    private final CaseEvents events;
    private final NumberService numbers;
    private final DeadlineService deadlines;
    private final CaseFileService cases;
    private final PartyService parties;
    private final CaseContentsService contents;
    private final HearingService hearings;
    private final CaseLinkService caseLinks;
    private final CaseTimelineService timeline;
    private final CommitteeService committees;
    private final MeetingService meetings;
    private final ActionItemService actions;
    private final ReferenceService references;

    public CaseModule(Settings s) {
        this.settings = s;
        this.config = new CaseConfigRegistry(s.dialect);
        this.events = new CaseEvents(s.dialect, s.outbox);
        this.numbers = new NumberService(s.dialect, s.zone);
        this.deadlines = new DeadlineService(s.dialect, s.tx, events, s.calendars, s.notifier, s.escalationSeats, s.zone);
        this.cases = new CaseFileService(s.dialect, config, numbers, events, deadlines, s.links);
        this.parties = new PartyService(s.dialect, config, events, s.partyDirectory);
        this.contents = new CaseContentsService(s.dialect, s.tx, events, s.content, s.links);
        this.hearings = new HearingService(s.dialect, events);
        this.caseLinks = new CaseLinkService(s.dialect, cases, parties, contents, deadlines, events);
        this.timeline = new CaseTimelineService(events, contents, s.history);
        this.committees = new CommitteeService(s.dialect, config, events);
        this.meetings = new MeetingService(s.dialect, config, committees, numbers, events, s.notifier, s.venues);
        this.actions = new ActionItemService(s.dialect, config, committees, meetings, deadlines, events, s.processes);
        this.references = new ReferenceService(s.dialect, config, numbers, deadlines, events, cases, contents, s.processes, s.notifier);
    }

    public static void install(CaseModule module) {
        installed = module;
    }

    public static CaseModule get() {
        CaseModule m = installed;
        if (m == null) {
            throw new IllegalStateException("CaseModule is not installed (sangam-server wiring)");
        }
        return m;
    }

    public static boolean isInstalled() {
        return installed != null;
    }

    public Settings settings() {
        return settings;
    }

    public Dialect dialect() {
        return settings.dialect;
    }

    public Transactions tx() {
        return settings.tx;
    }

    public CaseConfigRegistry config() {
        return config;
    }

    public CaseEvents events() {
        return events;
    }

    public NumberService numbers() {
        return numbers;
    }

    public DeadlineService deadlines() {
        return deadlines;
    }

    public CaseFileService cases() {
        return cases;
    }

    public PartyService parties() {
        return parties;
    }

    public CaseContentsService contents() {
        return contents;
    }

    public HearingService hearings() {
        return hearings;
    }

    public CaseLinkService caseLinks() {
        return caseLinks;
    }

    public CaseTimelineService timeline() {
        return timeline;
    }

    public CommitteeService committees() {
        return committees;
    }

    public MeetingService meetings() {
        return meetings;
    }

    public ActionItemService actions() {
        return actions;
    }

    public ReferenceService references() {
        return references;
    }
}
