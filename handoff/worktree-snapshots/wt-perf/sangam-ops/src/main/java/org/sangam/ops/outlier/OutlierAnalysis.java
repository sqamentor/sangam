// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.outlier;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Outlier analysis with significant variables. Per activity: a duration threshold of Q3 + k x IQR, then a comparison of each
 * variable value's frequency in outliers against the rest, with a one-sided Fisher exact test. Results describe correlation, not
 * cause; {@link Result#note()} says so. Below the minimum sample size no result is produced. No multiple-comparison correction is
 * applied, so p-values are a ranking aid and not a proof.
 */
public final class OutlierAnalysis {

    public record Sample(String instanceId, long durationMs, Map<String, String> variables) {
    }

    public record SignificantValue(String variable, String value, int inOutliers, int inOthers, double outlierRate, double otherRate, double lift, double pValue) {
    }

    public record Result(String activityId, boolean enoughData, int samples, long thresholdMs, int outliers, List<SignificantValue> variables, String note) {
    }

    private static final String NOTE = "Correlation only: these values occur more often in slow instances. They do not show the cause.";

    private OutlierAnalysis() {
    }

    public static Result analyse(String activityId, List<Sample> samples, int minSamples, double k, double maxP) {
        if (samples.size() < minSamples) {
            return new Result(activityId, false, samples.size(), 0, 0, List.of(), "fewer than " + minSamples + " samples");
        }
        long[] d = samples.stream().mapToLong(Sample::durationMs).sorted().toArray();
        double q1 = quantile(d, 0.25);
        double q3 = quantile(d, 0.75);
        long threshold = Math.round(q3 + k * (q3 - q1));
        List<Sample> slow = samples.stream().filter(s -> s.durationMs() > threshold).toList();
        List<Sample> rest = samples.stream().filter(s -> s.durationMs() <= threshold).toList();
        if (slow.isEmpty()) {
            return new Result(activityId, true, samples.size(), threshold, 0, List.of(), NOTE);
        }
        Map<String, Set<String>> values = new LinkedHashMap<>();
        samples.forEach(s -> s.variables().forEach((n, v) -> values.computeIfAbsent(n, x -> new java.util.TreeSet<>()).add(String.valueOf(v))));
        List<SignificantValue> out = new ArrayList<>();
        values.forEach((name, vals) -> vals.forEach(v -> {
            int a = count(slow, name, v);
            int c = count(rest, name, v);
            if (a == 0) {
                return;
            }
            double p = fisherOneSided(a, slow.size() - a, c, rest.size() - c);
            double rateSlow = (double) a / slow.size();
            double rateRest = rest.isEmpty() ? 0 : (double) c / rest.size();
            if (p <= maxP && rateSlow > rateRest) {
                out.add(new SignificantValue(name, v, a, c, rateSlow, rateRest, rateRest == 0 ? Double.POSITIVE_INFINITY : rateSlow / rateRest, p));
            }
        }));
        out.sort(Comparator.comparingDouble(SignificantValue::pValue));
        return new Result(activityId, true, samples.size(), threshold, slow.size(), out, NOTE);
    }

    private static int count(List<Sample> list, String name, String value) {
        return (int) list.stream().filter(s -> value.equals(s.variables().get(name))).count();
    }

    static double quantile(long[] sorted, double q) {
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo]);
    }

    /** P(X >= a) for the table [[a,b],[c,d]] with fixed margins. */
    static double fisherOneSided(int a, int b, int c, int d) {
        int row = a + b;
        int col = a + c;
        int n = a + b + c + d;
        double[] lf = new double[n + 1];
        for (int i = 2; i <= n; i++) {
            lf[i] = lf[i - 1] + Math.log(i);
        }
        double p = 0;
        for (int x = a; x <= Math.min(row, col); x++) {
            p += Math.exp(lf[row] + lf[n - row] + lf[col] + lf[n - col] - lf[n] - lf[x] - lf[row - x] - lf[col - x] - lf[n - row - col + x]);
        }
        return Math.min(1.0, p);
    }

    /**
     * Reads samples for one activity from the engine history. {@code allowedVariables} comes from the caller's R19 variable permission:
     * other variables are never read. Only instances the caller may read count: {@code instances} is the READ predicate over
     * {@code ACT_HI_PROCINST} alias {@code r} (for example {@code GlobalSearch.visibility("process-instance")}); empty means no
     * samples (fail closed). String, long and double values are used; others are skipped.
     */
    public static List<Sample> readSamples(Transactions tx, org.sangam.core.security.SecurityIdentity who,
                                           java.util.Optional<org.sangam.ops.search.GlobalSearch.Visibility> instances, String processKey, String activityId,
                                           Set<String> allowedVariables, int max) {
        if (instances.isEmpty()) {
            return List.of();
        }
        String tenantId = who.tenantId();
        return tx.inTransaction(c -> {
            org.sangam.core.layer.SqlPredicate visible = instances.get().visible(c, who);
            List<Object> qp = new ArrayList<>(Arrays.asList(tenantId, processKey, activityId));
            qp.addAll(visible.params());
            List<String[]> rows = Jdbc.query(c, "SELECT a.PROC_INST_ID_, a.DURATION_ FROM ACT_HI_ACTINST a JOIN ACT_HI_PROCINST r ON r.ID_ = a.PROC_INST_ID_"
                            + " WHERE a.TENANT_ID_=? AND a.PROC_DEF_KEY_=? AND a.ACT_ID_=? AND a.DURATION_ IS NOT NULL AND (" + visible.sql() + ") ORDER BY a.ID_",
                    rs -> new String[]{rs.getString(1), rs.getString(2)}, qp.toArray());
            List<String[]> limited = rows.size() > max ? rows.subList(0, max) : rows;
            Map<String, Map<String, String>> vars = new HashMap<>();
            if (!allowedVariables.isEmpty()) {
                String in = allowedVariables.stream().map(v -> "?").collect(Collectors.joining(","));
                List<Object> p = new ArrayList<>(Arrays.asList(tenantId, processKey));
                p.addAll(allowedVariables);
                Jdbc.query(c, "SELECT v.PROC_INST_ID_, v.NAME_, v.TEXT_, v.LONG_, v.DOUBLE_ FROM ACT_HI_VARINST v WHERE v.TENANT_ID_=? AND v.PROC_DEF_KEY_=? AND v.NAME_ IN (" + in + ")", rs -> {
                    String val = rs.getString(3) != null ? rs.getString(3) : rs.getString(4) != null ? rs.getString(4) : rs.getString(5);
                    if (val != null) {
                        vars.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(rs.getString(2), val);
                    }
                    return null;
                }, p.toArray());
            }
            return limited.stream().map(r -> new Sample(r[0], Long.parseLong(r[1]), vars.getOrDefault(r[0], Map.of()))).toList();
        });
    }
}
