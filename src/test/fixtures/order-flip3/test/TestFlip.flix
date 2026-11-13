mod TestFlip {
    use Assert.assertEq;

    // Not reducible to a closed form by the JIT, so the test takes tens of milliseconds in the baseline (the order floors at 5 ms).
    def spin(n: Int32, acc: Int32): Int32 = if (n <= 0) acc else spin(n - 1, Flip.inc(acc) * 31 + n)

    def down(n: Int32): Int32 = if (n <= 0) 0 else down(n - (Flip.inc(0) + 1) / 2)

    // Slow in the baseline, loops under every mutant of inc.
    @Test
    def testA1Loop(): Unit \ Assert =
        assertEq(expected = spin(30000000, 0), spin(30000000, 0));
        assertEq(expected = 0, down(100))

    // Fast, fails under every mutant of inc.
    @Test
    def testB2Kill(): Unit \ Assert = assertEq(expected = 2, Flip.inc(1))

    // Does not call inc: not selected for any mutant.
    @Test
    def testC3Other(): Unit \ Assert = assertEq(expected = 1, 1)
}
