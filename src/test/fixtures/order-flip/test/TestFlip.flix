mod TestFlip {
    use Assert.assertEq;

    def down(n: Int32): Int32 = if (n <= 0) 0 else down(n - (Flip.inc(0) + 1) / 2)

    // Slow in the baseline, loops (or runs very long) under every mutant of inc.
    @Test
    def testA1Loop(): Unit \ Assert = assertEq(expected = 0, down(60000000))

    // Fast, fails under every mutant of inc.
    @Test
    def testB2Kill(): Unit \ Assert = assertEq(expected = 2, Flip.inc(1))
}
