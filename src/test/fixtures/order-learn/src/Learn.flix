// Fixture for the test-order tests: four sites, each reached by three equally fast tests; only the last test (name order) kills.
mod Learn {
    pub def f(x: Int32): Int32 = x + 1

    pub def g(x: Int32): Int32 = x * 2

    pub def h(x: Int32): Int32 = x - 3

    pub def k(x: Int32): Int32 = x + 4
}
