package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;

import java.util.List;
import java.util.Map;

/** {@link TestOrder} on synthetic durations and histories: no worker, no timing. */
public final class TestOrderTest {
    static final long MS = 1_000_000L;

    public static void main(String[] args) {
        Check c = new Check("TestOrderTest");

        c.test("without history the shorter test goes first", () -> {
            TestOrder o = new TestOrder(Map.of("a", 400 * MS, "b", 10 * MS, "c", 100 * MS));
            eq(List.of("b", "c", "a"), o.order(List.of("a", "b", "c")));
        });

        c.test("equal keys keep the given order; durations under the floor are equal", () -> {
            TestOrder o = new TestOrder(Map.of("a", 1 * MS, "b", 4 * MS, "c", 0L));
            eq(List.of("a", "b", "c"), o.order(List.of("a", "b", "c")));
            eq(List.of("c", "b", "a"), o.order(List.of("c", "b", "a")));
            eq(List.of("x", "y"), new TestOrder(Map.of()).order(List.of("x", "y")));
        });

        c.test("a test that killed goes before an equally fast test that ran without killing", () -> {
            TestOrder o = new TestOrder(Map.of());
            o.record(List.of("a", "b", "c"), 3, true);
            eq(List.of("c", "a", "b"), o.order(List.of("a", "b", "c")));
        });

        c.test("tests after the killer did not run and stay unchanged", () -> {
            TestOrder o = new TestOrder(Map.of());
            o.record(List.of("a", "b", "c"), 2, true);
            // b killed (2/3), a ran without kill (1/3), c has no history (1/2).
            eq(List.of("b", "c", "a"), o.order(List.of("a", "b", "c")));
        });

        c.test("a slow test with a high kill rate can still go after a fast one with a low rate", () -> {
            TestOrder o = new TestOrder(Map.of("slow", 100 * MS, "fast", 10 * MS));
            for (int i = 0; i < 5; i++) o.record(List.of("slow"), 1, true);
            for (int i = 0; i < 5; i++) o.record(List.of("fast"), 1, false);
            // slow: (5+1)/(5+2) / 100 ms, fast: 1/7 / 10 ms: 0.0086 against 0.0143
            eq(List.of("fast", "slow"), o.order(List.of("slow", "fast")));
            for (int i = 0; i < 20; i++) o.record(List.of("fast"), 1, false);
            eq(List.of("slow", "fast"), o.order(List.of("slow", "fast")));
        });

        c.test("the order is a permutation of the input and the input is not changed", () -> {
            List<String> in = List.of("c", "a", "b");
            TestOrder o = new TestOrder(Map.of("a", 50 * MS));
            List<String> out = o.order(in);
            eq(List.of("c", "a", "b"), in);
            eq(List.of("a", "b", "c"), out.stream().sorted().toList());
        });

        c.test("record ignores a ran count beyond the sent tests", () -> {
            TestOrder o = new TestOrder(Map.of());
            o.record(List.of("a"), 5, false);
            // a counts once (1/3), b has no history (1/2)
            eq(List.of("b", "a"), o.order(List.of("a", "b")));
        });

        c.finish();
    }
}
