// Fixture for the test-order tests: two tests kill every mutant of inc; the slow one is first in name order, the fast one runs first.
mod Two {
    pub def inc(x: Int32): Int32 = x + 1
}
