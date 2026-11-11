package dev.flixw.mutate;

/** Checks the text between the two operands of an infix site. */
public final class Gap {
    private Gap() { }

    /**
     * True if {@code gap} (the text between the operands) is exactly the operator, with only blanks and
     * closing or opening parentheses around it. A comment, another token or a different operator
     * ({@code <=} for {@code <}) is false.
     */
    public static boolean isOperator(String gap, String operator) {
        throw new UnsupportedOperationException("not implemented");
    }
}
