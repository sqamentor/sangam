package org.sangam.spike.sim.pfms;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The simulator's own CSV formats for PFMS payment and status files (the real PFMS schemas are
 * ON-REQUEST, RQ-1 / PK-05; only this mapping changes when they arrive). UTF-8, LF or CRLF line
 * ends, comma separated, no quoting: fields must not contain commas or line breaks.
 *
 * <pre>
 * Payment file  PAY_&lt;ssid&gt;_&lt;batchId&gt;.csv   (product -&gt; outbound)
 *   H,&lt;ssid&gt;,&lt;schemeCode&gt;,&lt;batchId&gt;,&lt;recordCount&gt;,&lt;totalAmount&gt;
 *   D,&lt;operationId&gt;,&lt;billId&gt;,&lt;beneficiaryName&gt;,&lt;accountNo&gt;,&lt;ifsc&gt;,&lt;amount&gt;
 *
 * Status file   STS_&lt;batchId&gt;_&lt;seq&gt;.csv       (simulator -&gt; inbound; seq = 1, 2, ... per batch)
 *   H,&lt;batchId&gt;,&lt;paymentFileName&gt;,ACCEPTED|REJECTED,&lt;reason or empty&gt;
 *   D,&lt;operationId&gt;,&lt;pfmsRef or empty&gt;,SUCCESS|FAILED,&lt;reason or empty&gt;
 * </pre>
 * Amounts are decimals with 2 places (e.g. {@code 1500.00}).
 */
public final class PfmsFiles {

    private PfmsFiles() { }

    private static final Pattern SAFE = Pattern.compile("[^,\\r\\n]*");

    public record PaymentLine(String operationId, String billId, String beneficiaryName, String accountNo,
                              String ifsc, BigDecimal amount) { }

    public record PaymentFile(String ssid, String schemeCode, String batchId, List<PaymentLine> lines) {

        public PaymentFile {
            lines = List.copyOf(lines);
        }

        public BigDecimal total() {
            return lines.stream().map(PaymentLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public String fileName() {
            return "PAY_" + ssid + "_" + batchId + ".csv";
        }

        public String toCsv() {
            StringBuilder sb = new StringBuilder();
            sb.append(join("H", ssid, schemeCode, batchId, String.valueOf(lines.size()), money(total()))).append('\n');
            for (PaymentLine l : lines) {
                sb.append(join("D", l.operationId(), l.billId(), l.beneficiaryName(), l.accountNo(), l.ifsc(),
                        money(l.amount()))).append('\n');
            }
            return sb.toString();
        }
    }

    public record StatusLine(String operationId, String pfmsRef, String status, String reason) { }

    public record StatusFile(String batchId, String paymentFileName, String fileStatus, String reason,
                             List<StatusLine> lines) {

        public StatusFile {
            lines = List.copyOf(lines);
        }

        public String toCsv() {
            StringBuilder sb = new StringBuilder();
            sb.append(join("H", batchId, paymentFileName, fileStatus, nz(reason))).append('\n');
            for (StatusLine l : lines) {
                sb.append(join("D", l.operationId(), nz(l.pfmsRef()), l.status(), nz(l.reason()))).append('\n');
            }
            return sb.toString();
        }
    }

    /** Thrown for a payment or status file that does not follow the format. */
    public static final class FormatException extends Exception {
        public FormatException(String message) {
            super(message);
        }
    }

    public static PaymentFile parsePayment(String csv) throws FormatException {
        List<String[]> rows = rows(csv);
        if (rows.isEmpty() || !rows.get(0)[0].equals("H") || rows.get(0).length != 6) {
            throw new FormatException("first line must be H,<ssid>,<scheme>,<batchId>,<count>,<total>");
        }
        String[] h = rows.get(0);
        List<PaymentLine> lines = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            String[] r = rows.get(i);
            if (!r[0].equals("D") || r.length != 7) {
                throw new FormatException("line " + (i + 1) + ": expected D with 6 fields");
            }
            if (r[1].isBlank()) {
                throw new FormatException("line " + (i + 1) + ": operationId is empty");
            }
            lines.add(new PaymentLine(r[1], r[2], r[3], r[4], r[5], amount(r[6], i + 1)));
        }
        PaymentFile f = new PaymentFile(h[1], h[2], h[3], lines);
        int count;
        try {
            count = Integer.parseInt(h[4]);
        } catch (NumberFormatException e) {
            throw new FormatException("header count is not a number");
        }
        if (count != lines.size()) {
            throw new FormatException("COUNT_MISMATCH: header says " + count + ", file has " + lines.size());
        }
        if (amount(h[5], 1).compareTo(f.total()) != 0) {
            throw new FormatException("TOTAL_MISMATCH: header says " + h[5] + ", lines add up to " + money(f.total()));
        }
        return f;
    }

    public static StatusFile parseStatus(String csv) throws FormatException {
        List<String[]> rows = rows(csv);
        if (rows.isEmpty() || !rows.get(0)[0].equals("H") || rows.get(0).length != 5) {
            throw new FormatException("first line must be H,<batchId>,<file>,<status>,<reason>");
        }
        String[] h = rows.get(0);
        List<StatusLine> lines = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            String[] r = rows.get(i);
            if (!r[0].equals("D") || r.length != 5) {
                throw new FormatException("line " + (i + 1) + ": expected D with 4 fields");
            }
            lines.add(new StatusLine(r[1], emptyToNull(r[2]), r[3], emptyToNull(r[4])));
        }
        return new StatusFile(h[1], h[2], h[3], emptyToNull(h[4]), lines);
    }

    static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static BigDecimal amount(String s, int line) throws FormatException {
        try {
            BigDecimal v = new BigDecimal(s);
            if (v.scale() > 2 || v.signum() <= 0) {
                throw new FormatException("line " + line + ": amount must be positive with at most 2 decimals");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new FormatException("line " + line + ": amount is not a decimal: " + s);
        }
    }

    private static List<String[]> rows(String csv) {
        List<String[]> out = new ArrayList<>();
        for (String line : csv.split("\\r?\\n")) {
            if (!line.isBlank()) {
                out.add(line.split(",", -1));
            }
        }
        return out;
    }

    private static String join(String... fields) {
        for (String f : fields) {
            if (f == null || !SAFE.matcher(f).matches()) {
                throw new IllegalArgumentException("field contains a comma or line break: " + f);
            }
        }
        return String.join(",", fields);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
