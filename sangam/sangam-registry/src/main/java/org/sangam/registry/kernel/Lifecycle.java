// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.kernel;

import org.sangam.registry.RegistryException;
import org.sangam.registry.support.Maps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle state machine of a configured type. A transition names its source states ({@code *} = any non-terminal
 * state), its target, whether a reason is required, whether a second person must countersign (maker-checker) and
 * whether the state is held only {@code until} a date (suspension).
 */
public record Lifecycle(String initial, Map<String, State> states, List<Transition> transitions) {

    /** A state; {@code live} states count as in force (verification says VALID). */
    public record State(String key, boolean terminal, boolean live) {
    }

    public record Transition(String name, List<String> from, String to, boolean reasonRequired, boolean countersign,
                             boolean untilAllowed) {
        boolean appliesFrom(String state, Lifecycle lc) {
            if (from.contains(state)) {
                return true;
            }
            State s = lc.states().get(state);
            return from.contains("*") && s != null && !s.terminal();
        }
    }

    public static Lifecycle from(Map<String, Object> m, Lifecycle fallback) {
        if (m == null || m.isEmpty()) {
            return fallback;
        }
        Map<String, State> st = new LinkedHashMap<>();
        for (Map<String, Object> s : Maps.maps(m, "states")) {
            String key = Maps.required(s, "key");
            st.put(key, new State(key, Maps.bool(s, "terminal", false), Maps.bool(s, "live", false)));
        }
        List<Transition> tr = new ArrayList<>();
        for (Map<String, Object> t : Maps.maps(m, "transitions")) {
            tr.add(new Transition(Maps.required(t, "name"), Maps.strings(t, "from"), Maps.required(t, "to"),
                    Maps.bool(t, "reasonRequired", false), Maps.bool(t, "countersign", false),
                    Maps.bool(t, "untilAllowed", false)));
        }
        Lifecycle lc = new Lifecycle(Maps.str(m, "initial", st.isEmpty() ? "ACTIVE" : st.keySet().iterator().next()),
                java.util.Collections.unmodifiableMap(st), List.copyOf(tr));
        lc.check();
        return lc;
    }

    private void check() {
        if (!states.containsKey(initial)) {
            throw RegistryException.invalid("lifecycle: initial state " + initial + " is not declared");
        }
        for (Transition t : transitions) {
            if (!states.containsKey(t.to())) {
                throw RegistryException.invalid("lifecycle: transition " + t.name() + " targets unknown state " + t.to());
            }
            for (String f : t.from()) {
                if (!"*".equals(f) && !states.containsKey(f)) {
                    throw RegistryException.invalid("lifecycle: transition " + t.name() + " from unknown state " + f);
                }
            }
        }
    }

    public Map<String, Object> toMap() {
        return Maps.of("initial", initial,
                "states", states.values().stream().map(s -> Maps.of("key", s.key(), "terminal", s.terminal(), "live", s.live())).toList(),
                "transitions", transitions.stream().map(t -> Maps.of("name", t.name(), "from", t.from(), "to", t.to(),
                        "reasonRequired", t.reasonRequired(), "countersign", t.countersign(), "untilAllowed", t.untilAllowed())).toList());
    }

    /** The transition allowed from {@code state}, or a STATE error. */
    public Transition transition(String name, String state) {
        for (Transition t : transitions) {
            if (t.name().equals(name) && t.appliesFrom(state, this)) {
                return t;
            }
        }
        throw RegistryException.state("transition " + name + " is not allowed from state " + state);
    }

    public boolean has(String name) {
        return transitions.stream().anyMatch(t -> t.name().equals(name));
    }

    public boolean live(String state) {
        State s = states.get(state);
        return s != null && s.live();
    }

    public boolean terminal(String state) {
        State s = states.get(state);
        return s != null && s.terminal();
    }

    /** Names of the transitions possible from a state (for UIs and BPMN gateways). */
    public List<String> available(String state) {
        return transitions.stream().filter(t -> t.appliesFrom(state, this)).map(Transition::name).distinct().toList();
    }

    private static State s(String k, boolean terminal, boolean live) {
        return new State(k, terminal, live);
    }

    private static Transition t(String name, List<String> from, String to, boolean reason, boolean until) {
        return new Transition(name, from, to, reason, false, until);
    }

    /** A simple lifecycle for records and master data: ACTIVE, INACTIVE, ARCHIVED. */
    public static Lifecycle simple() {
        Map<String, State> st = new LinkedHashMap<>();
        st.put("ACTIVE", s("ACTIVE", false, true));
        st.put("INACTIVE", s("INACTIVE", false, false));
        st.put("ARCHIVED", s("ARCHIVED", true, false));
        return new Lifecycle("ACTIVE", java.util.Collections.unmodifiableMap(st), List.of(
                t("deactivate", List.of("ACTIVE"), "INACTIVE", true, false),
                t("activate", List.of("INACTIVE"), "ACTIVE", false, false),
                t("archive", List.of("*"), "ARCHIVED", true, false)));
    }

    /**
     * The default lifecycle of an issued instrument (licence, permit, certificate, registration):
     * ISSUED → ACTIVE → RENEWAL_DUE → (RENEWED back to ACTIVE | EXPIRED), with side exits SUSPENDED (reinstate),
     * REVOKED, SURRENDERED and TRANSFERRED (the holder change itself keeps the instrument ACTIVE; a transfer that
     * replaces the instrument ends it as TRANSFERRED).
     */
    public static Lifecycle instrument() {
        Map<String, State> st = new LinkedHashMap<>();
        st.put("ISSUED", s("ISSUED", false, true));
        st.put("ACTIVE", s("ACTIVE", false, true));
        st.put("RENEWAL_DUE", s("RENEWAL_DUE", false, true));
        st.put("SUSPENDED", s("SUSPENDED", false, false));
        st.put("EXPIRED", s("EXPIRED", false, false));
        st.put("REVOKED", s("REVOKED", true, false));
        st.put("SURRENDERED", s("SURRENDERED", true, false));
        st.put("TRANSFERRED", s("TRANSFERRED", true, false));
        List<String> live = List.of("ISSUED", "ACTIVE", "RENEWAL_DUE");
        return new Lifecycle("ISSUED", java.util.Collections.unmodifiableMap(st), List.of(
                t("activate", List.of("ISSUED"), "ACTIVE", false, false),
                t("renewalDue", List.of("ACTIVE"), "RENEWAL_DUE", false, false),
                t("renew", List.of("ACTIVE", "RENEWAL_DUE", "EXPIRED"), "ACTIVE", false, false),
                t("amend", live, "ACTIVE", false, false),
                t("transferHolder", live, "ACTIVE", true, false),
                t("suspend", live, "SUSPENDED", true, true),
                t("reinstate", List.of("SUSPENDED"), "ACTIVE", true, false),
                t("expire", List.of("ISSUED", "ACTIVE", "RENEWAL_DUE", "SUSPENDED"), "EXPIRED", false, false),
                t("revoke", List.of("ISSUED", "ACTIVE", "RENEWAL_DUE", "SUSPENDED", "EXPIRED"), "REVOKED", true, false),
                t("surrender", List.of("ISSUED", "ACTIVE", "RENEWAL_DUE", "SUSPENDED"), "SURRENDERED", true, false),
                t("transfer", live, "TRANSFERRED", true, false)));
    }

    /** States that are never "in force", for queries. */
    public Set<String> nonLiveStates() {
        Set<String> out = new java.util.HashSet<>();
        states.values().forEach(s -> {
            if (!s.live()) {
                out.add(s.key());
            }
        });
        return out;
    }
}
