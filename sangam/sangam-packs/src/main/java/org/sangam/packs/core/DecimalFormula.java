// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Decision 139: an arithmetic formula evaluated in exact decimal arithmetic. Grammar: numbers (decimal text, no
 * exponent), variable names ({@code [A-Za-z_][A-Za-z0-9_]*}), {@code + - * /}, unary minus, parentheses and the one
 * function {@code round(x)}, which applies the calculation's rounding policy to {@code x} (a component rounded before
 * it is summed, for example each contribution separately).
 *
 * <p>Addition, subtraction and multiplication are exact. Division is the only inexact step and uses the explicit
 * intermediate precision {@link #DIVISION} (34 significant digits, IEEE 754-2008 decimal128 precision, half-even): the
 * result keeps far more digits than any money amount, and the calculation's policy rounds it once at the end. No
 * binary floating point is used anywhere (decision 138). A missing variable, a non-decimal value or a division by zero
 * is refused, never defaulted.
 */
public final class DecimalFormula {

    /** The explicit intermediate precision of division (decision 139). */
    public static final MathContext DIVISION = MathContext.DECIMAL128;

    /** The longest accepted formula text. */
    public static final int MAX_LENGTH = 500;

    private final String text;
    private final Node root;
    private final Set<String> variables;
    private final boolean rounds;

    private DecimalFormula(String text, Node root, Set<String> variables, boolean rounds) {
        this.text = text;
        this.root = root;
        this.variables = Collections.unmodifiableSet(variables);
        this.rounds = rounds;
    }

    /** Parses {@code text}; a syntax error is refused with {@code FORMULA_INVALID}. */
    public static DecimalFormula parse(String text) {
        if (text == null || text.isBlank() || text.length() > MAX_LENGTH) {
            throw new FormulaException("FORMULA_INVALID", "a formula of 1 to " + MAX_LENGTH + " characters is required");
        }
        Parser p = new Parser(text);
        Node n = p.sum();
        p.skipBlanks();
        if (p.pos != text.length()) {
            throw p.error("unexpected '" + text.charAt(p.pos) + "'");
        }
        return new DecimalFormula(text, n, p.names, p.rounds);
    }

    public String text() {
        return text;
    }

    /** The variable names the formula reads, in order of first use. */
    public Set<String> variables() {
        return variables;
    }

    /** True when the formula rounds a component itself ({@code round(...)}). */
    public boolean roundsComponents() {
        return rounds;
    }

    /**
     * Evaluates the formula.
     *
     * @param values  the exact value of a variable; {@code null} means missing (refused with {@code FORMULA_MISSING})
     * @param rounder the rounding policy applied by {@code round(x)}
     */
    public BigDecimal evaluate(Function<String, BigDecimal> values, UnaryOperator<BigDecimal> rounder) {
        return root.eval(values, rounder);
    }

    /** A refused formula or evaluation, with a stable code. */
    public static final class FormulaException extends IllegalArgumentException {
        private final String code;

        public FormulaException(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    // ---- tree ----------------------------------------------------------------------------------------------------

    private interface Node {
        BigDecimal eval(Function<String, BigDecimal> values, UnaryOperator<BigDecimal> rounder);
    }

    private record Num(BigDecimal value) implements Node {
        @Override
        public BigDecimal eval(Function<String, BigDecimal> values, UnaryOperator<BigDecimal> rounder) {
            return value;
        }
    }

    private record Var(String name) implements Node {
        @Override
        public BigDecimal eval(Function<String, BigDecimal> values, UnaryOperator<BigDecimal> rounder) {
            BigDecimal v = values.apply(name);
            if (v == null) {
                throw new FormulaException("FORMULA_MISSING", "variable " + name + " has no value");
            }
            return v;
        }
    }

    private record Neg(Node inner) implements Node {
        @Override
        public BigDecimal eval(Function<String, BigDecimal> values, UnaryOperator<BigDecimal> rounder) {
            return inner.eval(values, rounder).negate();
        }
    }

    private record Round(Node inner) implements Node {
        @Override
        public BigDecimal eval(Function<String, BigDecimal> values, UnaryOperator<BigDecimal> rounder) {
            return rounder.apply(inner.eval(values, rounder));
        }
    }

    private record Bin(char op, Node left, Node right) implements Node {
        @Override
        public BigDecimal eval(Function<String, BigDecimal> values, UnaryOperator<BigDecimal> rounder) {
            BigDecimal l = left.eval(values, rounder);
            BigDecimal r = right.eval(values, rounder);
            return switch (op) {
                case '+' -> l.add(r);
                case '-' -> l.subtract(r);
                case '*' -> l.multiply(r);
                default -> {
                    if (r.signum() == 0) {
                        throw new FormulaException("FORMULA_DIVISION_BY_ZERO", "division by zero");
                    }
                    yield l.divide(r, DIVISION);
                }
            };
        }
    }

    // ---- parser (recursive descent) ------------------------------------------------------------------------------

    private static final class Parser {
        private final String s;
        private int pos;
        private int depth;
        private final Set<String> names = new LinkedHashSet<>();
        private boolean rounds;

        Parser(String s) {
            this.s = s;
        }

        FormulaException error(String why) {
            return new FormulaException("FORMULA_INVALID", why + " at " + pos + " in '" + s + "'");
        }

        void skipBlanks() {
            while (pos < s.length() && s.charAt(pos) == ' ') {
                pos++;
            }
        }

        boolean eat(char c) {
            skipBlanks();
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        Node sum() {
            Node n = product();
            while (true) {
                if (eat('+')) {
                    n = new Bin('+', n, product());
                } else if (eat('-')) {
                    n = new Bin('-', n, product());
                } else {
                    return n;
                }
            }
        }

        Node product() {
            Node n = unary();
            while (true) {
                if (eat('*')) {
                    n = new Bin('*', n, unary());
                } else if (eat('/')) {
                    n = new Bin('/', n, unary());
                } else {
                    return n;
                }
            }
        }

        Node unary() {
            if (eat('-')) {
                return new Neg(unary());
            }
            return atom();
        }

        Node atom() {
            skipBlanks();
            if (++depth > 50) {
                throw error("nesting too deep");
            }
            try {
                if (eat('(')) {
                    Node n = sum();
                    if (!eat(')')) {
                        throw error("')' expected");
                    }
                    return n;
                }
                if (pos >= s.length()) {
                    throw error("operand expected");
                }
                char c = s.charAt(pos);
                if (Character.isDigit(c)) {
                    int start = pos;
                    while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                        pos++;
                    }
                    String num = s.substring(start, pos);
                    if (!num.matches("[0-9]+(\\.[0-9]+)?")) {
                        throw error("not a number '" + num + "'");
                    }
                    return new Num(new BigDecimal(num));
                }
                if (Character.isLetter(c) || c == '_') {
                    int start = pos;
                    while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
                        pos++;
                    }
                    String name = s.substring(start, pos);
                    if (name.equals("round")) {
                        if (!eat('(')) {
                            throw error("'(' expected after round");
                        }
                        Node inner = sum();
                        if (!eat(')')) {
                            throw error("')' expected");
                        }
                        rounds = true;
                        return new Round(inner);
                    }
                    names.add(name);
                    return new Var(name);
                }
                throw error("unexpected '" + c + "'");
            } finally {
                depth--;
            }
        }
    }
}
