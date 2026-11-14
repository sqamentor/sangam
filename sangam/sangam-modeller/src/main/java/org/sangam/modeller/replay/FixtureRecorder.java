// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.replay;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.sangam.modeller.replay.ReplayException.Code;
import org.sangam.modeller.replay.ReplayFixture.Event;
import org.sangam.modeller.replay.ReplayFixture.EventType;
import org.sangam.modeller.replay.ReplayFixture.TaskOutcome;
import org.sangam.modeller.replay.ReplayFixture.Value;

/**
 * The capture side of decision 151: the engine adapter (W4-E) calls this while a scenario runs live on an isolated
 * engine, and {@link #finish(ReplayFixture.Observation)} turns the recording into a {@link ReplayFixture}.
 *
 * <ul>
 *   <li>{@code seq} is assigned here, in call order, so the fixture's order is the order the engine reported inputs;
 *       concurrent recording is serialised (the order then reflects the engine's own ordering, which the fixture keeps
 *       exactly as observed).</li>
 *   <li>The controlled clock never goes back: an event earlier than the previous one (or than {@code startedAt}) is
 *       refused at capture time, not discovered at replay.</li>
 *   <li>Nondeterminism the capture can neither record nor control (script randomness, a business number generator
 *       without a hook, an uncaptured lookup) is reported with {@link #unsupported(String)}; the fixture then carries
 *       the reasons and is refused for replay instead of being replayed approximately.</li>
 * </ul>
 */
public final class FixtureRecorder {

    /** Everything about the scenario that is fixed before it runs. */
    public record Header(String fixtureId, String scenario, boolean synthetic, List<ReplayFixture.ModelRef> models,
                         ReplayFixture.InitialState initialState, Map<String, String> configuration,
                         ReplayFixture.TenantContext tenant, ReplayFixture.Versions versions, String startedAt) {
    }

    private final Header header;
    private final List<Event> events = new ArrayList<>();
    private final Set<String> unsupported = new LinkedHashSet<>();
    private Instant clock;
    private long seq;
    private boolean finished;

    public FixtureRecorder(Header header) {
        this.header = Objects.requireNonNull(header, "header");
        this.clock = ReplayFixtures.instant(header.startedAt(), "startedAt");
    }

    /** Records one captured input; returns the event with its assigned {@code seq}. */
    public synchronized Event record(EventType type, String executionId, String activityId, int attempt, Instant at,
                                     TaskOutcome outcome, Integer retriesLeft, Map<String, Value> payload,
                                     String activityInstanceId) {
        open();
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(at, "at");
        if (at.isBefore(clock)) {
            throw new ReplayException(Code.INCOMPLETE_FIXTURE, type + " at " + at + " is before the previous event or "
                    + "startedAt " + clock + "; the captured clock must not go back");
        }
        Event e = new Event(++seq, type, executionId, activityId, attempt, at.toString(), outcome, retriesLeft, payload,
                activityInstanceId);
        events.add(e);
        clock = at;
        return e;
    }

    /** A clock read (FEEL {@code now()}/{@code today()}): the value read is the event's time. */
    public Event clockRead(String executionId, String activityId, Instant read) {
        return record(EventType.CLOCK_READ, executionId, activityId, 1, read, null, null, Map.of(), null);
    }

    /** Marks the scenario unsupported for replay; the reason names the uncontrolled source. */
    public synchronized void unsupported(String reason) {
        open();
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("an unsupported mark needs a reason");
        }
        unsupported.add(reason);
    }

    /** Events recorded so far, in capture order. */
    public synchronized List<Event> events() {
        return List.copyOf(events);
    }

    /**
     * Ends the recording with what the live run produced. A supported fixture is validated here (an incomplete one
     * fails now, at capture); an unsupported one is returned with its reasons and is refused by any replay.
     */
    public synchronized ReplayFixture finish(ReplayFixture.Observation observed) {
        open();
        finished = true;
        ReplayFixture f = new ReplayFixture(ReplayFixture.SCHEMA_VERSION, header.fixtureId(), header.scenario(),
                header.synthetic(), header.models(), header.initialState(), header.configuration(), header.tenant(),
                header.versions(), header.startedAt(), events, observed, List.copyOf(unsupported));
        if (unsupported.isEmpty()) {
            ReplayFixtures.validate(f);
        }
        return f;
    }

    private void open() {
        if (finished) {
            throw new IllegalStateException("the recording is finished");
        }
    }
}
