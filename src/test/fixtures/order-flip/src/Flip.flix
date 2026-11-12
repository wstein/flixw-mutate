// Fixture for the test-order tests: the slow test hangs under every mutant of inc, the fast test fails; name order runs the slow one first.
mod Flip {
    pub def inc(x: Int32): Int32 = x + 1
}
