mod TestBound {
    use Assert.assertEq;

    def spin(n: Int32, acc: Int32): Int32 = if (n <= 0) acc else spin(n - 1, acc * 31 + n)

    // Fast, first in name order, kills every mutant of inc: the ordered run ends here.
    @Test
    def testA1FastKill(): Unit \ Assert = assertEq(expected = 2, Bound.inc(1))

    // Slow, selected but not run: it comes after the killer in name order, so it does not widen the bound.
    @Test
    def testB2SlowKill(): Unit \ Assert = assertEq(expected = spin(40000000, 2), spin(40000000, Bound.inc(1)))
}
