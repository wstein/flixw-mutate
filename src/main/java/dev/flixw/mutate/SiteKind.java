package dev.flixw.mutate;

import java.util.List;
import java.util.Optional;

/**
 * The mutation operators of design option 4 that this version implements. A kind names the typed-AST
 * node it is found at, the source operator, the operators it is replaced by, and the helper in the
 * generated switch module that carries the mutants.
 */
public enum SiteKind {
    LESS("Order.less", "<", "less", Shape.COMPARE, List.of("<=", ">=")),
    LESS_EQUAL("Order.lessEqual", "<=", "lessEqual", Shape.COMPARE, List.of("<", ">")),
    GREATER("Order.greater", ">", "greater", Shape.COMPARE, List.of(">=", "<=")),
    GREATER_EQUAL("Order.greaterEqual", ">=", "greaterEqual", Shape.COMPARE, List.of(">", "<")),
    EQUAL("Eq.eq", "==", "equal", Shape.COMPARE, List.of("!=")),
    NOT_EQUAL("Eq.neq", "!=", "notEqual", Shape.COMPARE, List.of("==")),
    ADD("Add.add", "+", "add", Shape.ARITHMETIC, List.of("-")),
    SUBTRACT("Sub.sub", "-", "subtract", Shape.ARITHMETIC, List.of("+")),
    MULTIPLY("Mul.mul", "*", "multiply", Shape.ARITHMETIC, List.of("/")),
    DIVIDE("Div.div", "/", "divide", Shape.ARITHMETIC, List.of("*")),
    AND("Binary.And", "and", "conj", Shape.LAZY, List.of("or")),
    OR("Binary.Or", "or", "disj", Shape.LAZY, List.of("and")),
    NOT("Unary.Not", "not", "flip", Shape.NEGATION, List.of("(removed)"));

    /** How a site is rewritten. */
    public enum Shape { COMPARE, ARITHMETIC, LAZY, NEGATION }

    private final String typedAstName;
    private final String operator;
    private final String helper;
    private final Shape shape;
    private final List<String> mutations;

    SiteKind(String typedAstName, String operator, String helper, Shape shape, List<String> mutations) {
        this.typedAstName = typedAstName;
        this.operator = operator;
        this.helper = helper;
        this.shape = shape;
        this.mutations = mutations;
    }

    /** The name the worker reports: a trait signature ({@code Order.less}), {@code Binary.And} or {@code Unary.Not}. */
    public String typedAstName() { return typedAstName; }

    public String operator() { return operator; }

    public String helper() { return helper; }

    public Shape shape() { return shape; }

    /** The replacement operators, one mutant each (one or two). */
    public List<String> mutations() { return mutations; }

    public static Optional<SiteKind> byTypedAstName(String name) {
        for (SiteKind k : values()) if (k.typedAstName.equals(name)) return Optional.of(k);
        return Optional.empty();
    }
}
