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
}
