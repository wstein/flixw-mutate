package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;

public final class GapTest {
    public static void main(String[] args) {
        Check c = new Check("GapTest");
        c.test("the operator between blanks", () -> eq(true, Gap.isOperator(" + ", "+")));
        c.test("no blanks", () -> eq(true, Gap.isOperator("+", "+")));
        c.test("parentheses around the operands", () -> eq(true, Gap.isOperator(") + (", "+")));
        c.test("a line break", () -> eq(true, Gap.isOperator("\n   <= \n", "<=")));
        c.test("word operator", () -> eq(true, Gap.isOperator(" and ", "and")));
        c.test("a longer operator is not the shorter one", () -> eq(false, Gap.isOperator(" <= ", "<")));
        c.test("a shorter operator is not the longer one", () -> eq(false, Gap.isOperator(" < ", "<=")));
        c.test("a block comment in the gap", () -> eq(false, Gap.isOperator(" /* c */ + ", "+")));
        c.test("a line comment in the gap", () -> eq(false, Gap.isOperator(" // c\n + ", "+")));
        c.test("another operator", () -> eq(false, Gap.isOperator(" - ", "+")));
        c.test("a second token", () -> eq(false, Gap.isOperator(" + x + ", "+")));
        c.test("the arrow is not a minus", () -> eq(false, Gap.isOperator(" -> ", "-")));
        c.test("an empty gap", () -> eq(false, Gap.isOperator("", "+")));
        c.test("a word operator inside a name", () -> eq(false, Gap.isOperator(" android ", "and")));
        c.test("infix: plain operands", () -> eq(true, Gap.fitsInfix("", " + ", "", "+")));
        c.test("infix: a parenthesized left operand", () -> eq(true, Gap.fitsInfix("(", ") * ", "", "*")));
        c.test("infix: both operands parenthesized", () -> eq(true, Gap.fitsInfix("(", ") * (", ")", "*")));
        c.test("infix: two levels", () -> eq(true, Gap.fitsInfix("((", ")) + ((", "))", "+")));
        c.test("infix: an opening parenthesis without its partner", () -> eq(false, Gap.fitsInfix("(", " + ", "", "+")));
        c.test("infix: a closing parenthesis without its partner", () -> eq(false, Gap.fitsInfix("", " + ", ")", "+")));
        c.test("infix: counts differ", () -> eq(false, Gap.fitsInfix("((", ") * ", "", "*")));
        c.test("infix: a token in the prefix", () -> eq(false, Gap.fitsInfix("f(", ") + ", "", "+")));
        c.test("infix: a token in the suffix", () -> eq(false, Gap.fitsInfix("", " + ", ".x", "+")));
        c.test("infix: the operator must match", () -> eq(false, Gap.fitsInfix("", " - ", "", "+")));
        c.test("unary: plain", () -> eq(true, Gap.fitsUnary("not ", "not", "")));
        c.test("unary: parenthesized operand", () -> eq(true, Gap.fitsUnary("not (", "not", ")")));
        c.test("unary: unbalanced", () -> eq(false, Gap.fitsUnary("not (", "not", "")));
        c.test("unary: another keyword", () -> eq(false, Gap.fitsUnary("nothing ", "not", "")));
        c.test("unary: a comment after the keyword", () -> eq(false, Gap.fitsUnary("not /* c */ ", "not", "")));
        c.finish();
    }
}
