// Helpers that many tests reach (tests per site: all of them), a nullary def, and code no test reaches.
mod Helpers {
    pub def safeDiv(a: Int32, b: Int32): Int32 = if (b == 0) 0 else a / b

    pub def limit(): Int32 = 10 + 5

    pub def within(x: Int32): Bool = x >= 0 and x <= limit()

    // No test calls it: its sites are never reached.
    pub def neverCalled(x: Int32): Int32 = if (x > 100) x - 100 else x + 100
}
