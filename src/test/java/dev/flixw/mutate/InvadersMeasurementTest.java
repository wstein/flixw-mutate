package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.yes;

import java.util.List;

/** Checks the sample and comparison used by the real-project measurement, without running Flix. */
public final class InvadersMeasurementTest {
    public static void main(String[] args) throws Exception {
        Check c = new Check("InvadersMeasurementTest");
        c.test("the bounded sample uses sorted files in rounds and rejects invalid sizes", () -> {
            eq(List.of("a\t1", "b\t1", "a\t2"), InvadersMeasurement.sample(List.of("b\t2", "a\t2", "b\t1", "a\t1"), 3));
            for (int count : List.of(0, 4)) {
                boolean rejected = false;
                try { InvadersMeasurement.sample(List.of("a\t1", "b\t1", "c\t1"), count); }
                catch (IllegalArgumentException expected) { rejected = true; }
                yes(rejected, "accepted invalid sample size " + count);
            }
        });
        c.test("identity drift and changed order are rejected before comparison", () -> {
            for (List<String> other : List.of(List.of("a", "c"), List.of("b", "a"), List.of("a"))) {
                boolean rejected = false;
                try { InvadersMeasurement.requireSame(List.of("a", "b"), other); }
                catch (IllegalArgumentException expected) { rejected = true; }
                yes(rejected, "accepted identities " + other);
            }
            InvadersMeasurement.requireSame(List.of("a", "b"), List.of("a", "b"));
        });
        c.test("only full versus selected treats not-covered as survived", () -> {
            eq(true, InvadersMeasurement.sameOutcome(Outcome.NOT_COVERED, Outcome.SURVIVED, true));
            eq(false, InvadersMeasurement.sameOutcome(Outcome.NOT_COVERED, Outcome.SURVIVED, false));
            eq(false, InvadersMeasurement.sameOutcome(Outcome.KILLED, Outcome.TIMEOUT, false));
            eq(false, InvadersMeasurement.sameOutcome(Outcome.KILLED, Outcome.SURVIVED, true));
            eq(true, InvadersMeasurement.sameOutcome(Outcome.KILLED, Outcome.KILLED, false));
        });
        c.test("timeout execution counts are identified as upper bounds", () -> {
            eq("upper-bound", InvadersMeasurement.countKind(Outcome.TIMEOUT));
            eq("measured", InvadersMeasurement.countKind(Outcome.KILLED));
            eq("measured", InvadersMeasurement.countKind(Outcome.SURVIVED));
            eq("measured", InvadersMeasurement.countKind(Outcome.NOT_COVERED));
        });
        c.test("comparison allows a different killer and count but detects outcome or identity differences", () -> {
            String a = "result\ta\t1\t2\t+\t-\tKILLED\t4\t1\tmeasured\t7\tTest.fast";
            String b = "result\ta\t1\t2\t+\t-\tKILLED\t4\t3\tmeasured\t8\tTest.slow";
            eq(List.of(), InvadersMeasurement.differences(List.of(a), List.of(b), false));
            yes(!InvadersMeasurement.differences(List.of(a), List.of(b.replace("KILLED", "TIMEOUT")), false).isEmpty(), "missed outcome");
            boolean rejected = false;
            try { InvadersMeasurement.differences(List.of(a), List.of(b.replace("result\ta", "result\tb")), false); }
            catch (IllegalArgumentException expected) { rejected = true; }
            yes(rejected, "missed identity drift");
        });
        c.finish();
    }
}
