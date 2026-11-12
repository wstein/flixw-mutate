package dev.flixw.mutate;

import static dev.flixw.mutate.Check.throwsA;

/** The hook runs only while hits are recorded (the baseline run), not in the runs of the mutants. */
public final class SpliceTest {
    public static void main(String[] args) throws Exception {
        Check c = new Check("SpliceTest");
        MarkerState.publish(1);

        c.test("while no hits are recorded the hook is not called (it is not even looked up)", () -> {
            MarkerState.set(0, false);
            Splice.before(new SpliceTest());
        });

        c.test("while hits are recorded a program without the hook class fails loudly", () -> {
            MarkerState.set(0, true);
            try {
                throwsA(IllegalStateException.class, () -> Splice.before(new SpliceTest()));
            } finally {
                MarkerState.set(0, false);
            }
        });

        c.finish();
    }
}
