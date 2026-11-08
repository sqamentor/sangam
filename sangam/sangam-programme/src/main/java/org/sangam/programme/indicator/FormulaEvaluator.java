// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.indicator;

import org.sangam.programme.ProgrammeException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Safe arithmetic for indicator formulas and return rules (no script engine, ADR-D-8). Grammar:
 * <pre>
 * expr    := or
 * or      := and ('||' and)*
 * and     := cmp ('&amp;&amp;' cmp)*
 * cmp     := sum (('&lt;'|'&lt;='|'&gt;'|'&gt;='|'=='|'!=') sum)?
 * sum     := term (('+'|'-') term)*
 * term    := unary (('*'|'/') unary)*
 * unary   := '-' unary | primary
 * primary := number | identifier | func '(' expr (',' expr)* ')' | '(' expr ')'
 * func    := min | max | abs | round | div | coalesce | if
 * </pre>
 * Identifiers are indicator keys ({@code [A-Za-z_][A-Za-z0-9_.]*}). A missing value makes the result {@code null}
 * (unknown) except inside {@code coalesce}. Division by zero yields {@code null} ({@code div(a,b,d)} returns {@code d}).
 * Addition, subtraction and multiplication are exact {@link BigDecimal} arithmetic (money never rounds); a quotient is\n * exact when it terminates and otherwise carries 34 significant digits. Comparisons and logic return 1 or 0. Expressions are limited to 2,000 characters and 64 nesting levels.
 */
public final class FormulaEvaluator {

    /** Precision of a quotient that has no exact decimal form (1/3); +, -, * are always exact. */
    public static final MathContext MC = MathContext.DECIMAL128;
    private static final int MAX_ROUND_DECIMALS = 34;
    private static final int MAX_LEN = 2000;
    private static final int MAX_DEPTH = 64;

    private FormulaEvaluator() {
    }

    /** Evaluates {@code expr}; {@code values} returns null for an unknown identifier. */
    public static BigDecimal evaluate(String expr, Function<String, BigDecimal> values) {
        return new Parser(expr, values, null).parseAll();
    }

    /** True when the rule expression evaluates to non-zero; null when a value is missing. */
    public static Boolean test(String expr, Function<String, BigDecimal> values) {
        BigDecimal v = evaluate(expr, values);
        return v == null ? null : v.signum() != 0;
    }

    /** Identifiers referenced by {@code expr} (also checks the syntax). */
    public static Set<String> references(String expr) {
        Set<String> refs = new LinkedHashSet<>();
        new Parser(expr, k -> BigDecimal.ONE, refs).parseAll();
        return refs;
    }

    private static final class Parser {
        private final String s;
        private final Function<String, BigDecimal> values;
        private final Set<String> refs;
        private int pos;
        private int depth;

        Parser(String s, Function<String, BigDecimal> values, Set<String> refs) {
            if (s == null || s.isBlank()) {
                throw bad("empty expression");
            }
            if (s.length() > MAX_LEN) {
                throw bad("expression too long");
            }
            this.s = s;
            this.values = values;
            this.refs = refs;
        }

        BigDecimal parseAll() {
            BigDecimal v = or();
            skip();
            if (pos != s.length()) {
                throw bad("unexpected '" + s.charAt(pos) + "' at " + pos);
            }
            return v;
        }

        private BigDecimal or() {
            BigDecimal l = and();
            while (eat("||")) {
                BigDecimal r = and();
                l = (l == null || r == null) ? (truthy(l) || truthy(r) ? BigDecimal.ONE : null) : bool(l.signum() != 0 || r.signum() != 0);
            }
            return l;
        }

        private BigDecimal and() {
            BigDecimal l = cmp();
            while (eat("&&")) {
                BigDecimal r = cmp();
                l = (l == null || r == null) ? (isFalse(l) || isFalse(r) ? BigDecimal.ZERO : null) : bool(l.signum() != 0 && r.signum() != 0);
            }
            return l;
        }

        private BigDecimal cmp() {
            BigDecimal l = sum();
            String op = null;
            for (String o : new String[]{"<=", ">=", "==", "!=", "<", ">"}) {
                if (eat(o)) {
                    op = o;
                    break;
                }
            }
            if (op == null) {
                return l;
            }
            BigDecimal r = sum();
            if (l == null || r == null) {
                return null;
            }
            int c = l.compareTo(r);
            return bool(switch (op) {
                case "<=" -> c <= 0;
                case ">=" -> c >= 0;
                case "==" -> c == 0;
                case "!=" -> c != 0;
                case "<" -> c < 0;
                default -> c > 0;
            });
        }

        private BigDecimal sum() {
            BigDecimal l = term();
            while (true) {
                if (eat("+")) {
                    BigDecimal r = term();
                    l = l == null || r == null ? null : l.add(r);
                } else if (peekMinus()) {
                    pos++;
                    BigDecimal r = term();
                    l = l == null || r == null ? null : l.subtract(r);
                } else {
                    return l;
                }
            }
        }

        private BigDecimal term() {
            BigDecimal l = unary();
            while (true) {
                if (eat("*")) {
                    BigDecimal r = unary();
                    l = l == null || r == null ? null : l.multiply(r);
                } else if (eat("/")) {
                    BigDecimal r = unary();
                    l = l == null || r == null || r.signum() == 0 ? null : divide(l, r);
                } else {
                    return l;
                }
            }
        }

        private BigDecimal unary() {
            if (peekMinus()) {
                pos++;
                BigDecimal v = unary();
                return v == null ? null : v.negate();
            }
            return primary();
        }

        private BigDecimal primary() {
            if (++depth > MAX_DEPTH) {
                throw bad("expression nested too deeply");
            }
            try {
                skip();
                if (pos >= s.length()) {
                    throw bad("unexpected end");
                }
                char ch = s.charAt(pos);
                if (ch == '(') {
                    pos++;
                    BigDecimal v = or();
                    expect(')');
                    return v;
                }
                if (Character.isDigit(ch) || ch == '.') {
                    int start = pos;
                    while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                        pos++;
                    }
                    try {
                        return new BigDecimal(s.substring(start, pos));
                    } catch (NumberFormatException e) {
                        throw bad("bad number at " + start);
                    }
                }
                if (Character.isLetter(ch) || ch == '_') {
                    int start = pos;
                    while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_' || s.charAt(pos) == '.')) {
                        pos++;
                    }
                    String id = s.substring(start, pos);
                    skip();
                    if (pos < s.length() && s.charAt(pos) == '(') {
                        pos++;
                        List<BigDecimal> args = new ArrayList<>();
                        skip();
                        if (pos < s.length() && s.charAt(pos) != ')') {
                            args.add(or());
                            while (eat(",")) {
                                args.add(or());
                            }
                        }
                        expect(')');
                        return call(id, args);
                    }
                    if (refs != null) {
                        refs.add(id);
                    }
                    return values.apply(id);
                }
                throw bad("unexpected '" + ch + "' at " + pos);
            } finally {
                depth--;
            }
        }

        private BigDecimal call(String fn, List<BigDecimal> a) {
            switch (fn) {
                case "min", "max" -> {
                    if (a.isEmpty()) {
                        throw bad(fn + " needs arguments");
                    }
                    BigDecimal best = null;
                    for (BigDecimal v : a) {
                        if (v == null) {
                            return null;
                        }
                        best = best == null ? v : ("min".equals(fn) ? best.min(v) : best.max(v));
                    }
                    return best;
                }
                case "abs" -> {
                    arity(fn, a, 1);
                    return a.get(0) == null ? null : a.get(0).abs();
                }
                case "round" -> {
                    if (a.size() < 1 || a.size() > 2) {
                        throw bad("round(x[,decimals])");
                    }
                    if (a.get(0) == null || a.size() == 2 && a.get(1) == null) {
                        return null; // a missing decimals argument is unknown, not "0 decimals"
                    }
                    BigDecimal dec = a.size() == 2 ? a.get(1) : BigDecimal.ZERO;
                    if (dec.stripTrailingZeros().scale() > 0 || dec.abs().compareTo(BigDecimal.valueOf(MAX_ROUND_DECIMALS)) > 0) {
                        throw bad("round decimals must be a whole number in -" + MAX_ROUND_DECIMALS + ".." + MAX_ROUND_DECIMALS);
                    }
                    return a.get(0).setScale(dec.intValueExact(), RoundingMode.HALF_UP);
                }
                case "div" -> {
                    if (a.size() < 2 || a.size() > 3) {
                        throw bad("div(a,b[,default])");
                    }
                    BigDecimal dflt = a.size() == 3 ? a.get(2) : null;
                    if (a.get(0) == null || a.get(1) == null || a.get(1).signum() == 0) {
                        return dflt;
                    }
                    return divide(a.get(0), a.get(1));
                }
                case "coalesce" -> {
                    for (BigDecimal v : a) {
                        if (v != null) {
                            return v;
                        }
                    }
                    return null;
                }
                case "if" -> {
                    arity(fn, a, 3);
                    if (a.get(0) == null) {
                        return null;
                    }
                    return a.get(0).signum() != 0 ? a.get(1) : a.get(2);
                }
                default -> throw bad("unknown function " + fn);
            }
        }

        private void arity(String fn, List<BigDecimal> a, int n) {
            if (a.size() != n) {
                throw bad(fn + " takes " + n + " argument(s)");
            }
        }

        private boolean peekMinus() {
            skip();
            return pos < s.length() && s.charAt(pos) == '-';
        }

        private boolean eat(String tok) {
            skip();
            if (s.startsWith(tok, pos)) {
                // do not read "<" from "<=" etc.
                if ((tok.equals("<") || tok.equals(">")) && pos + 1 < s.length() && s.charAt(pos + 1) == '=') {
                    return false;
                }
                pos += tok.length();
                return true;
            }
            return false;
        }

        private void expect(char ch) {
            skip();
            if (pos >= s.length() || s.charAt(pos) != ch) {
                throw bad("expected '" + ch + "' at " + pos);
            }
            pos++;
        }

        private void skip() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        private static boolean truthy(BigDecimal v) {
            return v != null && v.signum() != 0;
        }

        private static boolean isFalse(BigDecimal v) {
            return v != null && v.signum() == 0;
        }

        private static BigDecimal bool(boolean b) {
            return b ? BigDecimal.ONE : BigDecimal.ZERO;
        }
    }

    /** Exact when the quotient terminates (10/4 = 2.5), otherwise rounded to {@link #MC} (HALF_EVEN). */
    static BigDecimal divide(BigDecimal a, BigDecimal b) {
        try {
            return a.divide(b);
        } catch (ArithmeticException nonTerminating) {
            return a.divide(b, MC);
        }
    }

    private static ProgrammeException bad(String message) {
        return new ProgrammeException(ProgrammeException.Code.INVALID_CONFIG, "formula: " + message);
    }
}
