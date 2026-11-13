mod TestTwo {
    use Assert.assertEq;

    def spin(n: Int32, acc: Int32): Int32 = if (n <= 0) acc else spin(n - 1, acc * 31 + n)

    // Slow, kills every mutant of inc (first in name order).
    @Test
    def testA1SlowKill(): Unit \ Assert = assertEq(expected = spin(40000000, 2), spin(40000000, Two.inc(1)))

    // Fast, kills every mutant of inc.
    @Test
    def testB2FastKill(): Unit \ Assert = assertEq(expected = 2, Two.inc(1))

    // Does not call inc: never selected, but part of the full run (three tests).
    @Test
    def testC3Other(): Unit \ Assert = assertEq(expected = 1, 1)
}
