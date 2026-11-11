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
        c.finish();
    }
}
