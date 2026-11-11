mod TestHot {
    use Assert.assertEq;

    @Test
    def testLoop(): Unit \ Assert = assertEq(expected = 16666667, Hot.loop(10000000, 0))

    @Test
    def testLoopMatch(): Unit \ Assert = assertEq(expected = 11250000, Hot.loopMatch(5000000, 0))

    @Test
    def testSumTo(): Unit \ Assert = assertEq(expected = 2999997, Hot.sumTo(1000000))
}
