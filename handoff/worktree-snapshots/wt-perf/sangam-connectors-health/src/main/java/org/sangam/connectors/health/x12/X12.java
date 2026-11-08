// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.x12;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * X12 005010 interchange model, parser and writer (US-HLT-090, US-HLT-091). Written from the X12 envelope rules; no
 * third-party EDI library.
 *
 * <ul>
 *   <li>The ISA segment is fixed width (106 characters with its terminator). Its 4th character is the element
 *       separator, ISA11 the repetition separator, ISA16 the component separator and the character after ISA16 the
 *       segment terminator. Line breaks after the terminator are tolerated.</li>
 *   <li>Envelope checks: IEA01 = number of GS, IEA02 = ISA13; GE01 = number of ST, GE02 = GS06; SE01 = segments from
 *       ST to SE inclusive, SE02 = ST02. Violations are reported in {@link Interchange#errors()} (they become 999 /
 *       TA1 rejections upstream), not thrown, so a partly broken file can still be acknowledged.</li>
 * </ul>
 */
public final class X12 {

    private X12() {
    }

    /** Separators of one interchange. */
    public record Delimiters(char element, char repetition, char component, char segment) {
        public static final Delimiters DEFAULT = new Delimiters('*', '^', ':', '~');
    }

    /** One segment: id plus elements; {@link #el(int)} is 1-based like the X12 reference designators (NM103 = el(3)). */
    public record Segment(String id, List<String> elements) {
        public Segment {
            Objects.requireNonNull(id, "id");
            elements = List.copyOf(elements);
        }

        public static Segment of(String id, String... els) {
            List<String> l = new ArrayList<>(Arrays.asList(els));
            for (int i = 0; i < l.size(); i++) {
                if (l.get(i) == null) {
                    l.set(i, "");
                }
            }
            // drop trailing empty elements (X12 rule)
            while (!l.isEmpty() && l.get(l.size() - 1).isEmpty()) {
                l.remove(l.size() - 1);
            }
            return new Segment(id, l);
        }

        public String el(int i) {
            return i >= 1 && i <= elements.size() ? elements.get(i - 1) : "";
        }

        /** Component {@code c} (1-based) of element {@code i}. */
        public String comp(int i, int c, Delimiters d) {
            String[] parts = el(i).split(java.util.regex.Pattern.quote(String.valueOf(d.component())), -1);
            return c >= 1 && c <= parts.length ? parts[c - 1] : "";
        }

        public String write(Delimiters d) {
            StringBuilder sb = new StringBuilder(id);
            for (String e : elements) {
                sb.append(d.element()).append(e);
            }
            return sb.append(d.segment()).toString();
        }
    }

    /** ST…SE. {@code segments} excludes ST and SE. */
    public record Transaction(Segment st, List<Segment> segments, Segment se) {
        public String type() {
            return st.el(1);
        }

        public String controlNumber() {
            return st.el(2);
        }

        public String implementation() {
            return st.el(3);
        }
    }

    /** GS…GE. */
    public record Group(Segment gs, List<Transaction> transactions, Segment ge) {
        public String functionalId() {
            return gs.el(1);
        }
    }

    /** ISA…IEA with any envelope errors found while parsing. */
    public record Interchange(Delimiters delimiters, Segment isa, List<Group> groups, Segment iea, List<String> errors) {
        public String controlNumber() {
            return isa.el(13);
        }

        public String sender() {
            return isa.el(6).trim();
        }

        public String receiver() {
            return isa.el(8).trim();
        }

        public List<Transaction> transactions() {
            List<Transaction> out = new ArrayList<>();
            groups.forEach(g -> out.addAll(g.transactions()));
            return out;
        }

        public boolean valid() {
            return errors.isEmpty();
        }
    }

    /** Thrown when the text is not an X12 interchange at all (no usable ISA). */
    public static final class X12Exception extends RuntimeException {
        public X12Exception(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------------------------------------ parsing

    public static Interchange parse(String text) {
        if (text == null) {
            throw new X12Exception("empty interchange");
        }
        String s = text.stripLeading();
        if (s.length() < 106 || !s.startsWith("ISA")) {
            throw new X12Exception("interchange does not start with a 106-character ISA segment");
        }
        char el = s.charAt(3);
        // ISA16 is the single character before the terminator at position 104; the terminator is at 105
        char comp = s.charAt(104);
        char seg = s.charAt(105);
        String[] isaEls = s.substring(0, 105).split(java.util.regex.Pattern.quote(String.valueOf(el)), -1);
        if (isaEls.length != 17) {
            throw new X12Exception("ISA must have 16 elements, found " + (isaEls.length - 1));
        }
        char rep = isaEls[11].length() == 1 ? isaEls[11].charAt(0) : '^';
        Delimiters d = new Delimiters(el, rep, comp, seg);
        List<Segment> segs = new ArrayList<>();
        for (String raw : s.split(java.util.regex.Pattern.quote(String.valueOf(seg)))) {
            String t = raw.strip();
            if (t.isEmpty()) {
                continue;
            }
            String[] parts = t.split(java.util.regex.Pattern.quote(String.valueOf(el)), -1);
            segs.add(new Segment(parts[0], Arrays.asList(parts).subList(1, parts.length)));
        }
        List<String> errors = new ArrayList<>();
        Segment isa = segs.get(0);
        List<Group> groups = new ArrayList<>();
        Segment iea = null;
        Segment gs = null;
        List<Transaction> txs = null;
        Segment st = null;
        List<Segment> body = null;
        for (int i = 1; i < segs.size(); i++) {
            Segment x = segs.get(i);
            switch (x.id()) {
                case "GS" -> {
                    if (gs != null) {
                        errors.add("GS " + gs.el(6) + " not closed by GE");
                    }
                    gs = x;
                    txs = new ArrayList<>();
                }
                case "ST" -> {
                    if (gs == null) {
                        errors.add("ST " + x.el(2) + " outside a functional group");
                        gs = Segment.of("GS");
                        txs = new ArrayList<>();
                    }
                    if (st != null) {
                        errors.add("ST " + st.el(2) + " not closed by SE");
                    }
                    st = x;
                    body = new ArrayList<>();
                }
                case "SE" -> {
                    if (st == null) {
                        errors.add("SE without ST");
                        continue;
                    }
                    int count = body.size() + 2;
                    if (!String.valueOf(count).equals(x.el(1))) {
                        errors.add("SE01 " + x.el(1) + " != " + count + " segments in ST " + st.el(2));
                    }
                    if (!st.el(2).equals(x.el(2))) {
                        errors.add("SE02 " + x.el(2) + " != ST02 " + st.el(2));
                    }
                    txs.add(new Transaction(st, body, x));
                    st = null;
                    body = null;
                }
                case "GE" -> {
                    if (gs == null) {
                        errors.add("GE without GS");
                        continue;
                    }
                    if (!String.valueOf(txs.size()).equals(x.el(1))) {
                        errors.add("GE01 " + x.el(1) + " != " + txs.size() + " transactions in GS " + gs.el(6));
                    }
                    if (!gs.el(6).equals(x.el(2))) {
                        errors.add("GE02 " + x.el(2) + " != GS06 " + gs.el(6));
                    }
                    groups.add(new Group(gs, txs, x));
                    gs = null;
                    txs = null;
                }
                case "IEA" -> {
                    iea = x;
                    if (!String.valueOf(groups.size()).equals(x.el(1))) {
                        errors.add("IEA01 " + x.el(1) + " != " + groups.size() + " groups");
                    }
                    if (!isa.el(13).equals(x.el(2))) {
                        errors.add("IEA02 " + x.el(2) + " != ISA13 " + isa.el(13));
                    }
                }
                case "TA1" -> {
                    // interchange acknowledgment segment directly in the envelope
                    if (body != null) {
                        body.add(x);
                    } else {
                        groups.add(new Group(Segment.of("GS", "TA"), List.of(new Transaction(Segment.of("ST", "TA1", "0"),
                                List.of(x), Segment.of("SE", "3", "0"))), Segment.of("GE", "1")));
                    }
                }
                default -> {
                    if (body == null) {
                        errors.add("segment " + x.id() + " outside a transaction set");
                    } else {
                        body.add(x);
                    }
                }
            }
        }
        if (st != null) {
            errors.add("ST " + st.el(2) + " not closed by SE");
        }
        if (gs != null) {
            errors.add("GS " + gs.el(6) + " not closed by GE");
        }
        if (iea == null) {
            errors.add("IEA missing");
        }
        return new Interchange(d, isa, groups, iea, errors);
    }

    // ------------------------------------------------------------------------------------------------ writing

    /** Envelope parameters for {@link #write}. */
    public record Envelope(String senderQualifier, String senderId, String receiverQualifier, String receiverId,
                           String appSender, String appReceiver, long interchangeControl, boolean production,
                           LocalDateTime at, boolean ackRequested) {
        public Envelope {
            Objects.requireNonNull(senderId, "senderId");
            Objects.requireNonNull(receiverId, "receiverId");
            senderQualifier = senderQualifier == null ? "ZZ" : senderQualifier;
            receiverQualifier = receiverQualifier == null ? "ZZ" : receiverQualifier;
            appSender = appSender == null ? senderId : appSender;
            appReceiver = appReceiver == null ? receiverId : appReceiver;
            at = at == null ? LocalDateTime.now(java.time.ZoneOffset.UTC) : at;
        }
    }

    /**
     * Writes one interchange with one functional group holding {@code bodies} (each the segments between ST and SE).
     *
     * @param functionalId GS01: HS (270), HB (271), HR (276), HN (277), HI (278), HC (837), HP (835), FA (999)
     * @param type         ST01, e.g. 270
     * @param version      GS08 / ST03, e.g. 005010X279A1
     */
    public static String write(Envelope env, String functionalId, String type, String version, List<List<Segment>> bodies,
                               Delimiters d) {
        Delimiters dl = d == null ? Delimiters.DEFAULT : d;
        String ctl = String.format("%09d", env.interchangeControl() % 1_000_000_000L);
        String date6 = env.at().format(DateTimeFormatter.ofPattern("yyMMdd"));
        String date8 = env.at().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String time = env.at().format(DateTimeFormatter.ofPattern("HHmm"));
        StringBuilder sb = new StringBuilder();
        sb.append("ISA").append(dl.element()).append("00").append(dl.element()).append(pad("", 10))
                .append(dl.element()).append("00").append(dl.element()).append(pad("", 10))
                .append(dl.element()).append(pad(env.senderQualifier(), 2)).append(dl.element()).append(pad(env.senderId(), 15))
                .append(dl.element()).append(pad(env.receiverQualifier(), 2)).append(dl.element()).append(pad(env.receiverId(), 15))
                .append(dl.element()).append(date6).append(dl.element()).append(time)
                .append(dl.element()).append(dl.repetition()).append(dl.element()).append("00501")
                .append(dl.element()).append(ctl).append(dl.element()).append(env.ackRequested() ? '1' : '0')
                .append(dl.element()).append(env.production() ? 'P' : 'T').append(dl.element()).append(dl.component())
                .append(dl.segment()).append('\n');
        String groupCtl = String.valueOf(env.interchangeControl() % 1_000_000_000L);
        sb.append(Segment.of("GS", functionalId, env.appSender(), env.appReceiver(), date8, time, groupCtl, "X", version)
                .write(dl)).append('\n');
        int n = 0;
        for (List<Segment> body : bodies) {
            n++;
            String stCtl = String.format("%04d", n);
            sb.append(Segment.of("ST", type, stCtl, version).write(dl)).append('\n');
            for (Segment x : body) {
                sb.append(x.write(dl)).append('\n');
            }
            sb.append(Segment.of("SE", String.valueOf(body.size() + 2), stCtl).write(dl)).append('\n');
        }
        sb.append(Segment.of("GE", String.valueOf(bodies.size()), groupCtl).write(dl)).append('\n');
        sb.append(Segment.of("IEA", "1", ctl).write(dl)).append('\n');
        return sb.toString();
    }

    static String pad(String s, int n) {
        String v = s == null ? "" : s;
        if (v.length() > n) {
            return v.substring(0, n);
        }
        return v + " ".repeat(n - v.length());
    }

    /** Removes X12 delimiter characters from a free-text value (names, addresses). */
    public static String clean(String v, Delimiters d) {
        if (v == null) {
            return "";
        }
        Delimiters dl = d == null ? Delimiters.DEFAULT : d;
        StringBuilder sb = new StringBuilder();
        for (char c : v.toCharArray()) {
            if (c != dl.element() && c != dl.component() && c != dl.segment() && c != dl.repetition() && c >= 0x20) {
                sb.append(c);
            }
        }
        return sb.toString().toUpperCase(java.util.Locale.ROOT);
    }
}
