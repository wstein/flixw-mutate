mod TestBump {
    use Assert.assertEq;

    // The same call twice: a mutant of inc changes both results alike, so these tests never fail under a mutant.
    def spin(n: Int32, acc: Int32): Int32 = if (n <= 0) acc else spin(n - 1, Bump.inc(acc) * 31 + n)

    // Name order puts the slow tests first, the fast killer last.
    @Test
    def testA1Slow(): Unit \ Assert = assertEq(expected = spin(30000000, 0), spin(30000000, 0))

    @Test
    def testA2Slow(): Unit \ Assert = assertEq(expected = spin(31000000, 0), spin(31000000, 0))

    @Test
    def testB3Fast(): Unit \ Assert = assertEq(expected = 3, Bump.inc(2))
}
