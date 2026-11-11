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
        int from = 0;
        int to = gap.length();
        while (from < to && skippable(gap.charAt(from))) from++;
        while (to > from && skippable(gap.charAt(to - 1))) to--;
        return gap.substring(from, to).equals(operator);
    }

    private static boolean skippable(char c) {
        return c == '(' || c == ')' || Character.isWhitespace(c);
    }

    /**
     * True if replacing the whole expression {@code prefix operand gap operand suffix} by a call that
     * contains only the operands leaves the parentheses balanced: {@code prefix} holds only blanks and
     * opening parentheses, {@code suffix} only blanks and closing ones, and each parenthesis the
     * replacement drops is matched inside the dropped text ({@code (a + b) * 2}: prefix {@code (},
     * gap {@code ) * }).
     */
    public static boolean fitsInfix(String prefix, String gap, String suffix, String operator) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** The same for a prefix operator: {@code prefix} is {@code not} then blanks and opening parentheses. */
    public static boolean fitsUnary(String prefix, String keyword, String suffix) {
        throw new UnsupportedOperationException("not implemented");
    }
}
